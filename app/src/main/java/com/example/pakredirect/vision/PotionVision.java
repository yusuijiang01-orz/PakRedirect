package com.example.pakredirect.vision;

import android.graphics.Bitmap;
import android.graphics.Rect;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Potion inventory adapter: only confirmed red/blue icon cells are returned. */
public final class PotionVision {
    public static final class PotionStack {
        public final int slot;
        public final InventoryVision.CellState color;
        public final DigitVision.Reading quantity;
        PotionStack(int slot,InventoryVision.CellState color,DigitVision.Reading quantity){
            this.slot=slot;this.color=color;this.quantity=quantity;
        }
        public boolean isActionable(){
            return (color==InventoryVision.CellState.RED_POTION||color==InventoryVision.CellState.BLUE_POTION)
                    && quantity!=null&&quantity.status==DigitVision.Status.CONFIRMED&&quantity.value!=null;
        }
    }
    public static final class Result {
        public final List<PotionStack> stacks;
        public final boolean complete;
        Result(List<PotionStack> stacks,boolean complete){
            this.stacks=Collections.unmodifiableList(stacks);this.complete=complete;
        }
    }

    private final InventoryVision inventory;
    public PotionVision(InventoryVision inventory){if(inventory==null)throw new IllegalArgumentException("inventory vision required");this.inventory=inventory;}

    public Result fromFusedGrid(InventoryVision.Grid grid,List<DigitVision.Reading> quantities){
        if(grid==null)throw new IllegalArgumentException("fused inventory grid required");
        List<PotionStack> stacks=new ArrayList<>();
        boolean complete=grid.complete;
        for(InventoryVision.Cell cell:grid.cells){
            if(cell.state==InventoryVision.CellState.RED_POTION||cell.state==InventoryVision.CellState.BLUE_POTION){
                DigitVision.Reading reading=quantities!=null&&cell.slot<=quantities.size()
                        ?quantities.get(cell.slot-1):null;
                stacks.add(new PotionStack(cell.slot,cell.state,reading));
                if(reading==null||reading.status!=DigitVision.Status.CONFIRMED
                        ||reading.value==null||reading.value<=0)complete=false;
            }else if(cell.state==InventoryVision.CellState.UNKNOWN)complete=false;
        }
        return new Result(stacks,complete);
    }

    public Result classify(Bitmap frame,Rect grid,Bitmap empty,Bitmap red,Bitmap blue,Bitmap other,
                           List<DigitVision.Reading> quantities,double threshold,double margin,
                           int insetX,int insetY){
        InventoryVision.Grid classified=inventory.classify35(frame,grid,empty,red,blue,other,
                threshold,margin,insetX,insetY);
        List<PotionStack> stacks=new ArrayList<>();
        boolean complete=classified.complete;
        for(InventoryVision.Cell cell:classified.cells){
            if(cell.state==InventoryVision.CellState.RED_POTION||cell.state==InventoryVision.CellState.BLUE_POTION){
                DigitVision.Reading reading=quantities!=null&&cell.slot<=quantities.size()
                        ?quantities.get(cell.slot-1):null;
                stacks.add(new PotionStack(cell.slot,cell.state,reading));
                if(reading==null||reading.status!=DigitVision.Status.CONFIRMED)complete=false;
            }else if(cell.state==InventoryVision.CellState.UNKNOWN)complete=false;
        }
        return new Result(stacks,complete);
    }
}
