package org.gearman.impl.worker;

import org.gearman.GearmanFunctionCallback;
import org.gearman.impl.core.GearmanCallbackResult;
import org.gearman.impl.core.GearmanPacket;
import org.gearman.impl.core.GearmanPacket.Magic;

class GearmanFunctionCallbackImpl <K, C extends GearmanCallbackResult> implements GearmanFunctionCallback {
	
	private final byte[] jobHandle;
	private final WorkerConnectionController<K,C> wcc;
	
	private boolean isComplete = false;
	
	GearmanFunctionCallbackImpl(byte[] jobHandle, WorkerConnectionController<K,C> wcc) {
		this.jobHandle = jobHandle;
		this.wcc = wcc;
	}

	@Override
	public synchronized void callbackWarning(byte[] warning) {
		if(this.isComplete()) throw new IllegalStateException("Job has completed");
		wcc.sendPacket(GearmanPacket.createWORK_WARNING(Magic.REQ, jobHandle, warning), null /*TODO*/);
	}

	@Override
	public synchronized void callbackData(byte[] data) {
		if(this.isComplete()) throw new IllegalStateException("Job has completed");		
		wcc.sendPacket(GearmanPacket.createWORK_DATA(Magic.REQ, jobHandle, data), null /*TODO*/);
	}

	@Override
	public synchronized void callbackStatus(long numerator, long denominator) {
		if(this.isComplete()) throw new IllegalStateException("Job has completed");
		wcc.sendPacket(GearmanPacket.createWORK_STATUS(Magic.REQ, jobHandle, numerator, denominator), null /*TODO*/);
	}
	
	synchronized void success(byte[] data) {
		if(this.isComplete()) throw new IllegalStateException("Job has completed");
		this.isComplete = true;
		wcc.sendPacket(GearmanPacket.createWORK_COMPLETE(Magic.REQ, jobHandle, data), null /*TODO*/);
	}
	
	synchronized void fail() {
		if(this.isComplete()) throw new IllegalStateException("Job has completed");
		this.isComplete = true;
		wcc.sendPacket(GearmanPacket.createWORK_FAIL(Magic.REQ, jobHandle), null /*TODO*/);
	}
	
	private boolean isComplete() {
		return isComplete;
	}

}
