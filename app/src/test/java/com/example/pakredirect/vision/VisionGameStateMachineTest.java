package com.example.pakredirect.vision;
import static org.junit.Assert.*;
import org.junit.Test;

public class VisionGameStateMachineTest {
    @Test public void unknownOrLowConfidenceNeverAuthorizesState() {
        VisionGameStateMachine m=new VisionGameStateMachine(2);
        assertEquals(VisionGameStateMachine.Decision.NEEDS_CONFIRMATION,
                m.observe(VisionGameStateMachine.State.INVENTORY,0.5,true));
        assertEquals(VisionGameStateMachine.State.UNKNOWN,m.state());
    }
    @Test public void recoveryIsBoundedAndMustBeExplicitlyConfirmed() {
        VisionGameStateMachine m=new VisionGameStateMachine(2);
        assertTrue(m.beginRecovery());
        m.recoverySucceeded(VisionGameStateMachine.State.INVENTORY);
        assertEquals(VisionGameStateMachine.State.INVENTORY,m.state());
        assertTrue(m.beginRecovery());assertTrue(m.beginRecovery());assertFalse(m.beginRecovery());
        m.recoveryFailed();
        assertEquals(VisionGameStateMachine.State.UNKNOWN,m.state());
    }
    @Test public void saleNeedsExplicitSaleConfirmState() {
        VisionGameStateMachine m=new VisionGameStateMachine();
        assertFalse(m.allowsSaleConfirmation());
        m.observe(VisionGameStateMachine.State.INVENTORY,0.99,true);
        assertFalse(m.allowsSaleConfirmation());
        m.observe(VisionGameStateMachine.State.SALE_CONFIRM,0.99,true);
        assertTrue(m.allowsSaleConfirmation());
    }
}
