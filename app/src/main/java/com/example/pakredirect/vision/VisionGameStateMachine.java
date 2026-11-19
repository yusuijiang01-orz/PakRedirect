package com.example.pakredirect.vision;

/** Small explicit state machine shared by script workflows; UNKNOWN never authorizes input. */
public final class VisionGameStateMachine {
    public enum State { UNKNOWN, MAP, SHOP, INVENTORY, ITEM_DETAIL, SALE_CONFIRM, DIALOG, RECOVERING }
    public enum Decision { ACCEPTED, NEEDS_CONFIRMATION, REJECTED }
    private State state=State.UNKNOWN;
    private State previous=State.UNKNOWN;
    private int recoveryAttempts;
    private final int maximumRecoveryAttempts;

    public VisionGameStateMachine(){this(3);}
    public VisionGameStateMachine(int maximumRecoveryAttempts){
        if(maximumRecoveryAttempts<0||maximumRecoveryAttempts>10)throw new IllegalArgumentException("invalid retry limit");
        this.maximumRecoveryAttempts=maximumRecoveryAttempts;
    }

    public synchronized Decision observe(State candidate,double confidence,boolean independentFrame){
        if(candidate==null||candidate==State.UNKNOWN||!Double.isFinite(confidence)||confidence<0.85d)
            return Decision.NEEDS_CONFIRMATION;
        if(!independentFrame && candidate!=state)return Decision.NEEDS_CONFIRMATION;
        if(candidate==state)return Decision.ACCEPTED;
        if(state==State.RECOVERING){previous=candidate;return Decision.NEEDS_CONFIRMATION;}
        previous=state;state=candidate;recoveryAttempts=0;
        return Decision.ACCEPTED;
    }

    public synchronized boolean beginRecovery(){
        if(recoveryAttempts>=maximumRecoveryAttempts)return false;
        recoveryAttempts++;previous=state;state=State.RECOVERING;return true;
    }
    public synchronized void recoverySucceeded(State recovered){
        if(recovered==null||recovered==State.UNKNOWN||recovered==State.RECOVERING)
            throw new IllegalArgumentException("recovery target must be a known state");
        previous=State.RECOVERING;state=recovered;recoveryAttempts=0;
    }
    public synchronized void recoveryFailed(){state=State.UNKNOWN;}
    public synchronized boolean canRecover(){return recoveryAttempts<maximumRecoveryAttempts;}
    public synchronized int recoveryAttempts(){return recoveryAttempts;}
    public synchronized State state(){return state;}
    public synchronized State previous(){return previous;}
    public synchronized boolean allowsInventoryMutation(){
        // A selected-item detail overlay and a sale dialog are not a clean
        // inventory grid. Only the verified INVENTORY state authorizes slot mutation.
        return state==State.INVENTORY;
    }
    public synchronized boolean allowsSaleConfirmation(){return state==State.SALE_CONFIRM;}
}
