package org.gearman;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.gearman.GearmanJobStatus.OperationResult;
import org.gearman.GearmanLostConnectionPolicy.Grounds;
import org.gearman.core.GearmanCompletionHandler;
import org.gearman.core.GearmanConnection;
import org.gearman.core.GearmanConnectionHandler;
import org.gearman.core.GearmanFailureHandler;
import org.gearman.core.GearmanPacket;
import org.gearman.util.ByteArray;

/** 
 * @author isaiah
 */
abstract class JobServerPoolAbstract <X extends JobServerPoolAbstract.ConnectionController<?>> implements GearmanJobServerPool {
	private static final String DEFAULT_CLIENT_ID = "-";
	
	public enum ControllerState {
		
		/**
		 * The state specifying the ConnectionController is in the processes of connecting to a server<br>
		 * <br>
		 * You can enter this state from the CLOSED and WAITING state
		 */
		CONNECTING,
		
		/**
		 * The state specifying the ConnectionController is connected to a job server<br>
		 * <br>
		 * You can enter this state from the CONNECTING state.
		 */
		OPEN,
		
		/**
		 * The state specifying the ConnectionController is not connected to a job server<br>
		 * <br>
		 * You can enter this state from the CONNECTING, OPEN, CLOSED, and WAITING states. This is the initial state.
		 */
		CLOSED,
		
		/**
		 * The state specifying the ConnectionController is no longer in regular use or controlled by
		 * the ServiceClient
		 * <br>
		 *  You can enter this state from the CONNECTING, OPEN, CLOSED, DROPPED, and WAITING states. This is the final state 
		 */
		DROPPED,
		
		/**
		 * The state specifying the ConnectionController is in a suggested timeout period. It is
		 * suggested that we wait out a period time before attempting to move to the OPEN state.
		 * However, this is only a suggestion. It is legal to move into the OPEN state.   
		 * <br>
		 * You can enter this state from the CONNECTING, OPEN, CLOSED, and WAITING states.
		 */
		WAITING
	};
				
	/**
	 * Controls connections to job servers.
	 * 
	 * @author isaiah
	 *
	 * @param <K>
	 */
	abstract static class ConnectionController<K> implements GearmanConnectionHandler<Object>, GearmanFailureHandler<Object>, Delayed {
		private final JobServerPoolAbstract<?> sc;
		
		/** The key mapping to this object in the connMap */
		private final K key;
		private ControllerState userState = ControllerState.CLOSED;
		private GearmanConnection<?> conn;
		private ScheduledFuture<?> future;
		private Closer closer;
		
		private ControllerState pjsState = ControllerState.CLOSED;
		private ConcurrentHashMap<ByteArray, JobStatus> pendingJobStatus; 
		
		private final Object lock = new Object();
		
		ConnectionController(JobServerPoolAbstract<?> sc, K key) {
			this.key = key;
			this.sc = sc;
		}
		
		@Override
		public final void onAccept(final GearmanConnection<Object> conn) {
			
			synchronized(this.lock) {
				final ControllerState oldState = this.userState;
				if(this.userState.equals(ControllerState.CONNECTING) && !this.sc.isShutdown) {
					
					// Normal execution
					assert this.conn==null;
					
					this.userState = ControllerState.OPEN;
					this.conn = conn;
					this.onOpen(oldState);
					
					if(!this.sc.id.equals(JobServerPoolAbstract.DEFAULT_CLIENT_ID)) {
						this.conn.sendPacket(GearmanPacket.createSET_CLIENT_ID(this.sc.id), null, null);
					}
					
				} else {
					// If not in the CONNECTING state when a connection is accepted, then the
					// user has changed the state while in the connection proccess.
					
					// Since all other state assume a closed connection, close the connection
					try {
						conn.close();
					} catch (IOException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
					
					if(this.sc.isShutdown)
						this.dropServer();
				}
			}
		}
		
		private final void userOnAccept() {
		}
		
		@Override
		public final void onDisconnect(final GearmanConnection<Object> conn) {
			synchronized(this.lock) {
				if(!this.userState.equals(ControllerState.OPEN)) return;
				
				// If we disconnect from the OPEN state, then we have unexpectedly disconnected
				this.closeServer();
				this.onLostConnection(sc.policy, Grounds.UNEXPECTED_DISCONNECT);
			}
		}
		
		@Override
		public final void onFail(final Throwable exc, final Object attachment) {
			synchronized(this.lock) {
				assert this.conn==null;
				
				if(this.sc.isShutdown)
					this.dropServer();
				else {
					this.closeServer();
					this.onLostConnection(sc.policy, Grounds.FAILED_CONNECTION);
				}
			}
		}
		
		public final void dropServer() {
			synchronized(this.lock) {
				final ControllerState oldState = this.userState;
				if(this.userState.equals(ControllerState.DROPPED)) return;
				
				this.userState = ControllerState.DROPPED;
				sc.connMap.remove(key);
				
				if(this.conn!=null) {
					try {
						conn.close();
					} catch (IOException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
				}
				
				this.onDrop(oldState);
			}
		}
		
		/**
		 * Tell this ConnectionController that it has had a response timeout and
		 * should close the connection
		 */
		protected final void timeout() {
			synchronized(this.lock) {
				if(this.userState.equals(ControllerState.OPEN)) {
					this.closeServer();
					this.onLostConnection(sc.policy, Grounds.RESPONCE_TIMEOUT);
				}
			}
		}
		
		public final void closeServer() {
			synchronized(this.lock) {
				ControllerState oldState = this.userState;
				this.userState = ControllerState.CLOSED;
				
				switch(oldState) {
				case CLOSED:
				case DROPPED:
					return;
				case OPEN:
					assert this.conn!=null;
					try {
						this.conn.close();
					} catch (IOException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
					this.conn = null;
					break;
				case WAITING:
					if(this.future!=null) {
						this.future.cancel(true);
						this.future = null;
					}
					break;
				case CONNECTING:
					break;
				}
				
				this.onClose(oldState);
			}
		}
		
		/**
		 * Attempts to put the ConnectionController in the {@link ControllerState#WAITING} state.<br>
		 * <br>
		 * The {@link ControllerState#WAITING} is simply a timed state that waits a persiod of time
		 * before moving to the {@link ControllerState#CLOSED}. Moving from the {@link ControllerState#WAITING}
		 * state to the {@link ControllerState#OPEN} is legal but not suggested.<br>
		 * <br>
		 * Connection controllers are typically put into the {@link ControllerState#WAITING} after a
		 * connectivity failure. We wait hoping that after the given period has elapsed, the job
		 * server will be running properly.<br>
		 * <br>
		 * The waiting period is specified the reconnect time, {@link GearmanJobServerPool#getReconnectPeriod(TimeUnit)}. 
		 * 
		 * @param callback
		 * 		A runnable that executes if the waiting period expires without before the state changes.
		 * 		If the state changes before the waiting period expires, the callback will not execute.
		 * 		This includes moving from the WAITING state to the WAITING state.
		 */
		public final void waitServer(final Runnable callback) {
			this.waitServer(callback, sc.waitPeriod, TimeUnit.NANOSECONDS);
		}
		
		/**
		 * Attempts to put the ConnectionController in the {@link ControllerState#WAITING} state.<br>
		 * <br>
		 * The {@link ControllerState#WAITING} is simply a timed state that waits a persiod of time
		 * before moving to the {@link ControllerState#CLOSED}. Moving from the {@link ControllerState#WAITING}
		 * state to the {@link ControllerState#OPEN} is legal but not suggested.<br>
		 * <br>
		 * Connection controllers are typically put into the {@link ControllerState#WAITING} after a
		 * connectivity failure. We wait hoping that after the given period has elapsed, the job
		 * server will be running properly.
		 * 
		 * @param callback
		 * 		A runnable that executes if the waiting period expires before the state changes.
		 * 		If the state changes before the waiting period expires, the callback will not execute.
		 * 		This includes moving from the WAITING state to the WAITING state.
		 * @param timeout
		 * 		The waiting period
		 * @param unit
		 * 		The time unit for the waiting period
		 */
		public final void waitServer(final Runnable callback, long waittime, final TimeUnit unit) {
			synchronized(this.lock) {
				if(this.closer==null) this.closer = new Closer();
				this.closer.setCallback(callback);
				
				final ControllerState oldState = this.userState;
				switch(this.userState) {
				case DROPPED:
					return;
				case WAITING:
					assert this.future!=null;
					this.future.cancel(true);
					break;
				case CONNECTING:
				case OPEN:
					this.closeServer();
					break;
				case CLOSED:
					break;
				}
				
				this.userState = ControllerState.WAITING;
				sc.getGearman().getPool().schedule(this.closer, waittime, unit);
				this.onWait(oldState);
			}
		}
		
		public final K getKey() {
			return this.key;
		}
		
		public ControllerState getState() {
			return this.userState;
		}
		
		public GearmanConnection<?> getConnection() {
			return this.conn;
		}
		
		/**
		 * Attempts to connect to a job server iff the current state allows it.<br>
		 * <br>
		 * If in the {@link ControllerState#OPEN} or {@link ControllerState#DROPPED} state, no attempt will be made to connect. If the <code>force</code>
		 * parameter is set to <code>true</code>, then it an attempt if in the {@link ControllerState#WAITING} or {@link ControllerState#CLOSED} state.
		 * Otherwise, an attempt will only be made if in the {@link ControllerState#CLOSED} state.<br>
		 * 
		 * @param force
		 * 		Will attempt to connect even if in the WAITING state
		 * @return
		 * 		<code>true</code> if an attempt is being made. If <code>true</code> the state of
		 * 		this ConnectionController will {@link ControllerState#OPEN} when this method
		 * 		exits. If <code>false</code> the state will be the same as it was before the
		 * 		invocation
		 */
		public final boolean openServer(final boolean force) {
			synchronized(this.lock) {
				
				final ControllerState oldState = this.userState; 
				switch(this.userState) {
				case CONNECTING:
				case OPEN:
				case DROPPED:
					return false;
				case WAITING:
					if(!force) return false;
				case CLOSED:
					this.userState = ControllerState.CONNECTING;
					this.onConnect(oldState);
					return true;
				default:
					assert false;
					return false;
				}
			}
		}
		
		@Override
		public long getDelay(TimeUnit unit) {
			final ScheduledFuture<?> f  = this.future;
			return f==null? -1: f.getDelay(unit);
		}
		
		@Override
		public int compareTo(Delayed o) {
			final ScheduledFuture<?> f  = this.future;
			return f==null? 0 : f.compareTo(o);
		}
		
		protected abstract void onConnect(ControllerState oldState);
		protected abstract void onOpen(ControllerState oldState);
		protected abstract void onClose(ControllerState oldState);
		protected abstract void onDrop(ControllerState oldState);
		protected abstract void onWait(ControllerState oldState);
		protected abstract void onNew();
		
		public final JobStatus createStatus(final ByteArray jobHandle) {
			
			switch(this.userState) {
			case CONNECTING:
			case OPEN:
			case WAITING:
			case CLOSED:
				break;
			case DROPPED:
				return null;
			default:
				assert false;
				return null;
			}
			
			if(pendingJobStatus==null)
				pendingJobStatus=new ConcurrentHashMap<ByteArray, JobStatus>();
			else {
				JobStatus js = pendingJobStatus.get(jobHandle);
				if(js!=null) return js;
			}
			
			assert !pendingJobStatus.containsKey(jobHandle);
						
			final JobStatus js = new JobStatus() {
				
				@Override
				protected void onComplete(OperationResult opResult) {
					if(!opResult.isSuccessful()) {
						pendingJobStatus.remove(jobHandle);
					}
				}
				
			};
			
			pendingJobStatus.containsKey(jobHandle);
			assert pendingJobStatus.containsKey(jobHandle);
			
			return js;
		}
		
		private final synchronized void sendStatusRequest(final ByteArray jobHandle) {
			assert this.userState.equals(ControllerState.OPEN);
			this.conn.sendPacket(GearmanPacket.createGET_STATUS(jobHandle.getBytes()), null, new GearmanCompletionHandler<Object>(){
				@Override
				public void onComplete(Object attachment) { }
				
				@Override
				public void onFail(Throwable exc, Object attachment) {
					final JobStatus js = pendingJobStatus.remove(jobHandle);
					if(js!=null) js.complete(OperationResult.OPERATION_REQUEST_FAILED, false, false, 0, 0);
				}
			});			
		}
		
		public final JobStatus removeStatus(final ByteArray jobHandle) {
			return pendingJobStatus==null? null: pendingJobStatus.remove(jobHandle);
		}
		
		protected abstract void onLostConnection(GearmanLostConnectionPolicy policy, Grounds grounds);
		
		private final class Closer implements Runnable {
			private Runnable callback;
			
			@Override
			public void run() {
				synchronized(ConnectionController.this.lock) {
					if(ConnectionController.this.userState.equals(ControllerState.WAITING) && !Thread.currentThread().isInterrupted()) {
						ConnectionController.this.userState = ControllerState.CLOSED;
						if(this.callback!=null) this.callback.run();
						
						ConnectionController.this.future=null;
					}
				}
			}
			
			public void setCallback(Runnable callback) {
				this.callback = callback;
			}
		}
		
	}
	
	private final ConcurrentHashMap<Object, X> connMap = new ConcurrentHashMap<Object,X>();
	private final GearmanLostConnectionPolicy defaultPolicy;
	private GearmanLostConnectionPolicy policy;;
	private long waitPeriod;
	private boolean isShutdown = false;
	private String id = JobServerPoolAbstract.DEFAULT_CLIENT_ID;
	
	JobServerPoolAbstract(GearmanLostConnectionPolicy defaultPolicy, long waitPeriod, TimeUnit unit) {
		this.defaultPolicy = defaultPolicy;
		this.policy = defaultPolicy;
		this.waitPeriod = unit.toNanos(waitPeriod);
	}
	
	@Override
	public boolean addServer(GearmanServer srvr) {
		if(this.isShutdown) throw new IllegalStateException("In Shutdown State");
		
		final X x = this.createController(srvr);
		if(this.connMap.putIfAbsent(srvr, x)==null) {
			x.onNew();
			return true;
		} else {
			return false;
		}
	}

	@Override
	public boolean addServer(InetSocketAddress adrs) {
		if(this.isShutdown) throw new IllegalStateException("In Shutdown State");
		
		final X x = this.createController(adrs);
		if(this.connMap.putIfAbsent(adrs, x)==null) {
			x.onNew();
			return true;
		} else {
			return false;
		}
	}

	@Override
	public String getClientID() {
		return this.id;
	}

	@Override
	public long getReconnectPeriod(TimeUnit unit) {
		return unit.convert(this.waitPeriod,TimeUnit.NANOSECONDS);
	}

	@Override
	public int getServerCount() {
		return this.connMap.size();
	}

	@Override
	public boolean hasServer(InetSocketAddress address) {
		return this.connMap.containsKey(address);
	}

	@Override
	public boolean hasServer(GearmanServer srvr) {
		return this.connMap.containsKey(srvr);
	}

	@Override
	public void removeAllServers() {
		Iterator<X> it = this.connMap.values().iterator();
		X value;
		
		while(it.hasNext()) {
			value = it.next();
			it.remove();
			
			if(value!=null) {
				value.dropServer();
			}
		}
	}

	@Override
	public boolean removeServer(GearmanServer srvr) {
		final X x = this.connMap.get(srvr);
		
		if(x!=null) {
			x.dropServer();
			return true;
		} else {
			return false;
		}
	}

	@Override
	public boolean removeServer(InetSocketAddress adrs) {
		final X x = this.connMap.get(adrs);
		
		if(x!=null) {
			x.dropServer();
			return true;
		} else {
			return false;
		}
	}

	@Override
	public void setClientID(String id) {
		if(this.isShutdown) throw new IllegalStateException("In Shutdown State");
		if(this.id.equals(id)) return;
		
		GearmanConnection<?> conn;
		for(X x : this.connMap.values()) {
			if((conn = x.getConnection())!=null) {
				conn.sendPacket(GearmanPacket.createSET_CLIENT_ID(id), null, null);
			}
		}
	}

	@Override
	public void setLostConnectionPolicy(GearmanLostConnectionPolicy policy) {
		if(this.isShutdown) throw new IllegalStateException("In Shutdown State");
		
		if(this.policy==null)
			this.policy = this.defaultPolicy;
		else
			this.policy = policy;
	}

	@Override
	public void setReconnectPeriod(long time, TimeUnit unit) {
		if(this.isShutdown) throw new IllegalStateException("In Shutdown State");
		this.waitPeriod = unit.toNanos(time);
	}

	@Override
	public boolean isShutdown() {
		return this.isShutdown;
	}

	@Override
	public synchronized void shutdown() {
		if(this.isShutdown) return;
		this.isShutdown = true;
		
		this.removeAllServers();
	}
	
	protected Map<Object,X> getConnections() {
		return Collections.unmodifiableMap(this.connMap);
	}
	protected GearmanLostConnectionPolicy getDefaultPolicy() {
		return this.defaultPolicy;
	}
	
	/**
	 * Creates a new ConnectionControler to add to the JobServerPool<br>
	 * Note: The returned value is not guaranteed to be added.  
	 * @param key
	 * 		The ConnectionControler's key
	 * @return
	 * 		
	 */
	protected abstract X createController(GearmanServer key);
	
	/**
	 * 
	 * @param key
	 * @return
	 */
	protected abstract X createController(InetSocketAddress key);
}