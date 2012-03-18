package org.gearman.impl.worker;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.gearman.GearmanFunction;
import org.gearman.GearmanLostConnectionAction;
import org.gearman.GearmanLostConnectionGrounds;
import org.gearman.GearmanLostConnectionPolicy;
import org.gearman.GearmanServer;
import org.gearman.GearmanWorker;
import org.gearman.impl.GearmanImpl;
import org.gearman.impl.core.GearmanConnectionManager.ConnectCallbackResult;
import org.gearman.impl.server.GearmanServerInterface;
import org.gearman.impl.serverpool.ControllerState;
import org.gearman.impl.serverpool.JobServerPoolAbstract;

public class GearmanWorkerImpl extends JobServerPoolAbstract<WorkerConnectionController<?,?>> implements GearmanWorker {
	
	private static final long HEARTBEAT_PERIOD = 20000000000L;
	
	/**
	 * Periodically checks the state of the connections while jobs can be pulled 
	 * @author isaiah
	 */
	private final class Heartbeat implements Runnable {
		
		@Override
		public void run() {
			final long time = System.currentTimeMillis();
			for(WorkerConnectionController<?,?> cc : GearmanWorkerImpl.super.getConnections().values()) {
				switch(cc.getState()) {
				case CONNECTING:
					// If connecting, nothing to do until a connection is established
				case DROPPED:
					// If dropped, the controller id
				case WAITING:
					break;
				case OPEN:
					cc.timeoutCheck(time);
					break;
				case CLOSED:
					cc.openServer(false);
					break;
				default:
					assert false;
				}
			}
		}
	}
	
	private class InnerConnectionController extends WorkerConnectionController<GearmanServerInterface, ConnectCallbackResult> {
		
		private final class Reconnector implements Runnable {
			@Override
			public void run() {
				if(!GearmanWorkerImpl.this.funcMap.isEmpty()) {
					InnerConnectionController.super.openServer(false);
				}
			}
		}
		
		private Reconnector r;
		
		InnerConnectionController(GearmanServerInterface key) {
			super(GearmanWorkerImpl.this, key);
		}

		@Override
		public void onOpen(ControllerState oldState) {
			if(GearmanWorkerImpl.this.funcMap.isEmpty()) {
				super.closeServer();
			} else {
				super.onOpen(oldState);
			}
		}
		
		@Override
		protected Dispatcher getDispatcher() {
			return GearmanWorkerImpl.this.dispatcher;
		}

		@Override
		protected GearmanWorkerImpl getWorker() {
			return GearmanWorkerImpl.this;
		}

		@Override
		protected void onConnect(ControllerState oldState) {
			super.getKey().createGearmanConnection(this, this);
		}

		@Override
		protected void onLostConnection(GearmanLostConnectionPolicy policy, GearmanLostConnectionGrounds grounds) {
			GearmanServer server = this.getKey();
			if(server==null) {
				// TODO log error
			}
			
			if(server.isLocalServer()) {
				policy.lostLocalServer(server, grounds);
			} else {
				GearmanLostConnectionAction action; 
				try {
					action = policy.lostRemoteServer(server, grounds);
				} catch (Throwable t) {
					action = null;
				}
				
				if(action==null) {
					action = GearmanWorkerImpl.super.getDefaultPolicy().lostRemoteServer(super.getKey(), grounds);
				} 
				
				switch(action) {
				case DROP:
					super.dropServer();
					break;
				case RECONNECT:
					super.waitServer(r==null? (r=new Reconnector()): r);
					break;
				default:
					throw new IllegalStateException("Unknown Action: " + action);
				}
			}
		}

		@Override
		protected void onDrop(ControllerState oldState) {
			// No cleanup required
		}

		@Override
		protected void onNew() {
			if(!GearmanWorkerImpl.this.funcMap.isEmpty()) {
				super.openServer(false);
			}
		}

		@Override
		protected void onClose(ControllerState oldState) { }

		@Override
		protected void onWait(ControllerState oldState) { }
	}
	
	private final class FunctionInfo {
		private final GearmanFunction function;
		// private final long timeout;
		
		public FunctionInfo(GearmanFunction function, long timeout) {
			this.function = function;
			//this.timeout = timeout;
		}
	}
	
	private final Dispatcher dispatcher = new Dispatcher();
	private final ConcurrentHashMap<String, FunctionInfo> funcMap = new ConcurrentHashMap<String, FunctionInfo>();
	
	private final Heartbeat heartbeat = new Heartbeat();
	private ScheduledFuture<?> future;
	
	private boolean isConnected = false;
	
	public GearmanWorkerImpl(final GearmanImpl gearman) {
		super(gearman, new GearmanLostConnectionPolicyImpl(), 60, TimeUnit.SECONDS);
	}

	@Override
	protected WorkerConnectionController<?,?> createController(GearmanServerInterface key) {
		return new InnerConnectionController(key);
	}

	/*
	@Override
	protected WorkerConnectionController<?,?> createController(InetSocketAddress key) {
		return new RemoteConnectionController(key);
	}
	*/

	@Override
	public GearmanFunction addFunction(String name, GearmanFunction function) {
		return this.addFunction(name, function, 0, TimeUnit.MILLISECONDS);
	}

	
	public final GearmanFunction addFunction(String name, GearmanFunction function, long timeout, TimeUnit unit) {
		if(name==null || function==null) throw new IllegalArgumentException("null paramiter");
		
		final FunctionInfo newFunc = new FunctionInfo(function, unit.toMillis(timeout));
		
		synchronized(this.funcMap) {
			final FunctionInfo oldFunc = this.funcMap.put(name, newFunc);
			
			if(oldFunc!=null) return oldFunc.function;
			if(this.isConnected) return null;
			
			this.isConnected = true;
			
			this.future = super.getGearman().getScheduler().scheduleAtFixedRate(this.heartbeat, HEARTBEAT_PERIOD, HEARTBEAT_PERIOD, TimeUnit.NANOSECONDS);
			for(WorkerConnectionController<?,?> w : super.getConnections().values()) {
				w.openServer(false);
			}
			
			return null;
		}
	}

	@Override
	public GearmanFunction getFunction(String name) {
		final FunctionInfo info = this.funcMap.get(name);
		return info==null? null: info.function;
	}

	/*
	@Override
	public long getFunctionTimeout(String name) {
		final FunctionInfo info = this.funcMap.get(name);
		return info==null? -1: info.timeout;
	}
	*/

	@Override
	public int getMaximumConcurrency() {
		return this.dispatcher.getMaxCount();
	}

	@Override
	public Set<String> getRegisteredFunctions() {
		return Collections.unmodifiableSet(this.funcMap.keySet());
	}

	@Override
	public boolean removeFunction(String functionName) {
		synchronized(this.funcMap) {
			final FunctionInfo info = this.funcMap.remove(functionName);
			if(info==null) return false;
			
			if(this.funcMap.isEmpty()) {
				this.isConnected = false;
				if(this.future!=null) future.cancel(false);
				for(WorkerConnectionController<?,?> conn : super.getConnections().values()) {
					conn.closeServer();
				}
			} else {
				for(WorkerConnectionController<?,?> conn : super.getConnections().values()) {
					conn.cantDo(functionName);
				}
			}
			return true;
		}
	}

	@Override
	public void setMaximumConcurrency(int maxConcurrentJobs) {
		this.dispatcher.setMaxCount(maxConcurrentJobs);
	}

	@Override
	public void removeAllServers() {
		
		/*
		 * TODO As of right now, when a worker has no function, connections are immediately closed.
		 * I'd like to see them timeoutt. This way if the user adds 
		 */
		synchronized(this.funcMap) {
			if(this.future!=null) future.cancel(false);
		}
		
		super.removeAllServers();
	}
	
	@Override
	public void shutdown() {
		super.shutdown();
		//TODO gearman.onServiceShutdown(this);
	}

	@Override
	public void removeAllFunctions() {
		synchronized(this.funcMap) {
			this.funcMap.clear();
			if(this.future!=null) future.cancel(false);
		}
	}
}
