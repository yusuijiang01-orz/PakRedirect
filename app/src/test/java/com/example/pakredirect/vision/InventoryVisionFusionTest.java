package com.example.pakredirect.vision;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public final class InventoryVisionFusionTest {
    private static InventoryVision.Grid grid(InventoryVision.CellState[] states,double confidence,double margin) {
        List<InventoryVision.Cell> cells=new ArrayList<>();
        for(int i=0;i<35;i++) {
            InventoryVision.CellState state=states[i];
            cells.add(new InventoryVision.Cell(i+1,i/5,i%5,state,
                    state==InventoryVision.CellState.UNKNOWN?0d:confidence,
                    state==InventoryVision.CellState.UNKNOWN?0d:margin));
        }
        return new InventoryVision.Grid(cells);
    }

    private static InventoryVision.CellState[] all(InventoryVision.CellState state) {
        InventoryVision.CellState[] values=new InventoryVision.CellState[35];
        java.util.Arrays.fill(values,state);
        return values;
    }

    @Test public void twoMatching35SlotFramesProduceCompleteGrid() {
        InventoryVision.CellState[] states=all(InventoryVision.CellState.EMPTY);
        states[2]=InventoryVision.CellState.RED_POTION;
        states[33]=InventoryVision.CellState.BLUE_POTION;
        InventoryVision.Grid fused=InventoryVision.fuse(grid(states,.96,.12),grid(states,.95,.11),.90,.08);
        assertTrue(fused.complete);
        assertEquals(33,fused.count(InventoryVision.CellState.EMPTY));
        assertEquals(1,fused.count(InventoryVision.CellState.RED_POTION));
        assertEquals(1,fused.count(InventoryVision.CellState.BLUE_POTION));
    }

    @Test public void disagreementOrUnknownNeverBecomesEmpty() {
        InventoryVision.CellState[] first=all(InventoryVision.CellState.EMPTY);
        InventoryVision.CellState[] second=all(InventoryVision.CellState.EMPTY);
        first[14]=InventoryVision.CellState.RED_POTION;
        second[14]=InventoryVision.CellState.EMPTY;
        second[20]=InventoryVision.CellState.UNKNOWN;
        InventoryVision.Grid fused=InventoryVision.fuse(grid(first,.99,.2),grid(second,.99,.2),.9,.1);
        assertFalse(fused.complete);
        assertEquals(InventoryVision.CellState.UNKNOWN,fused.cells.get(14).state);
        assertEquals(InventoryVision.CellState.UNKNOWN,fused.cells.get(20).state);
    }

    @Test public void potionStacksRequireConfirmedPositiveQuantity() {
        InventoryVision.CellState[] states=all(InventoryVision.CellState.EMPTY);
        states[0]=InventoryVision.CellState.RED_POTION;
        states[34]=InventoryVision.CellState.BLUE_POTION;
        InventoryVision.Grid fused=InventoryVision.fuse(grid(states,.98,.2),grid(states,.97,.19),.9,.1);
        List<DigitVision.Reading> readings=new ArrayList<>();
        for(int i=0;i<35;i++) readings.add(new DigitVision.Reading(DigitVision.Status.UNKNOWN,null,0,""));
        readings.set(0,new DigitVision.Reading(DigitVision.Status.CONFIRMED,12,.97,"12"));
        readings.set(34,new DigitVision.Reading(DigitVision.Status.CONFIRMED,8,.96,"8"));
        PotionVision.Result result=new PotionVision(new InventoryVision(new VisionMatcher())).fromFusedGrid(fused,readings);
        assertTrue(result.complete);
        assertEquals(2,result.stacks.size());
        assertTrue(result.stacks.get(0).isActionable());
        assertTrue(result.stacks.get(1).isActionable());
    }

    @Test public void unknownPotionQuantityMakesResultIncomplete() {
        InventoryVision.CellState[] states=all(InventoryVision.CellState.EMPTY);
        states[5]=InventoryVision.CellState.RED_POTION;
        InventoryVision.Grid fused=InventoryVision.fuse(grid(states,.98,.2),grid(states,.97,.19),.9,.1);
        List<DigitVision.Reading> readings=new ArrayList<>();
        for(int i=0;i<35;i++) readings.add(new DigitVision.Reading(DigitVision.Status.UNKNOWN,null,0,"?"));
        PotionVision.Result result=new PotionVision(new InventoryVision(new VisionMatcher())).fromFusedGrid(fused,readings);
        assertFalse(result.complete);
        assertFalse(result.stacks.get(0).isActionable());
    }
}
