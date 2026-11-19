package com.example.pakredirect.vision;

import android.graphics.Bitmap;
import android.graphics.Rect;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Independent 5x7 inventory classifier. Missing/ambiguous cells are UNKNOWN, never EMPTY. */
public final class InventoryVision {
    public enum CellState { EMPTY, RED_POTION, BLUE_POTION, OTHER, UNKNOWN }
    public static final class Cell {
        public final int slot,row,column;
        public final CellState state;
        public final double confidence,margin;
        Cell(int slot,int row,int column,CellState state,double confidence,double margin){
            this.slot=slot;this.row=row;this.column=column;this.state=state;
            this.confidence=confidence;this.margin=margin;
        }
    }
    public static final class Grid {
        public final List<Cell> cells;
        public final boolean complete;
        Grid(List<Cell> cells){this.cells=Collections.unmodifiableList(cells);
            boolean ok=cells.size()==35;for(Cell c:cells)if(c.state==CellState.UNKNOWN)ok=false;complete=ok;}
        public int count(CellState state){int n=0;for(Cell c:cells)if(c.state==state)n++;return n;}
    }

    private final VisionMatcher matcher;
    public InventoryVision(VisionMatcher matcher){if(matcher==null)throw new IllegalArgumentException("matcher required");this.matcher=matcher;}

    public Grid classify35(Bitmap frame,Rect grid,Bitmap empty,Bitmap red,Bitmap blue,Bitmap other,
                           double threshold,double minimumMargin,int insetX,int insetY){
        if(frame==null||frame.isRecycled()||grid==null||grid.width()<35||grid.height()<49)
            throw new IllegalArgumentException("invalid inventory frame/grid");
        Rect bounds=new Rect(Math.max(0,grid.left),Math.max(0,grid.top),
                Math.min(frame.getWidth(),grid.right),Math.min(frame.getHeight(),grid.bottom));
        if(bounds.width()<35||bounds.height()<49)throw new IllegalArgumentException("inventory grid outside screenshot");
        int cellW=bounds.width()/5,cellH=bounds.height()/7;
        List<Cell> results=new ArrayList<>(35);
        for(int slot=1;slot<=35;slot++){
            int row=(slot-1)/5,col=(slot-1)%5;
            Rect cell=new Rect(bounds.left+col*cellW+insetX,bounds.top+row*cellH+insetY,
                    bounds.left+(col+1)*cellW-insetX,bounds.top+(row+1)*cellH-insetY);
            if(cell.width()<6||cell.height()<6){results.add(new Cell(slot,row,col,CellState.UNKNOWN,0,0));continue;}
            double[] scores={
                    score(frame,empty,cell),score(frame,red,cell),score(frame,blue,cell),score(frame,other,cell)
            };
            int best=-1;double high=-1,second=-1;
            for(int i=0;i<scores.length;i++){
                if(scores[i]>high){second=high;high=scores[i];best=i;}
                else if(scores[i]>second)second=scores[i];
            }
            double margin=high-second;
            CellState state=CellState.UNKNOWN;
            if(best>=0&&high>=threshold&&margin>=minimumMargin){
                state=best==0?CellState.EMPTY:best==1?CellState.RED_POTION:
                        best==2?CellState.BLUE_POTION:CellState.OTHER;
            }
            results.add(new Cell(slot,row,col,state,Math.max(0,high),Math.max(0,margin)));
        }
        return new Grid(results);
    }

    public static Grid fuse(Grid first,Grid second,double threshold,double margin){
        if(first==null||second==null||first.cells.size()!=35||second.cells.size()!=35)
            throw new IllegalArgumentException("two complete 35-slot observations are required");
        List<Cell> fused=new ArrayList<>(35);
        for(int i=0;i<35;i++){
            Cell a=first.cells.get(i),b=second.cells.get(i);
            boolean stable=a.state==b.state&&a.state!=CellState.UNKNOWN
                    &&a.confidence>=threshold&&b.confidence>=threshold
                    &&a.margin>=margin&&b.margin>=margin;
            fused.add(new Cell(a.slot,a.row,a.column,stable?a.state:CellState.UNKNOWN,
                    stable?Math.min(a.confidence,b.confidence):0d,
                    stable?Math.min(a.margin,b.margin):0d));
        }
        return new Grid(fused);
    }

    private double score(Bitmap frame,Bitmap template,Rect cell){
        if(template==null||template.isRecycled())return -1d;
        List<VisionMatcher.Candidate> candidates=matcher.candidates(frame,template,cell,
                template.getWidth(),template.getHeight(),false);
        double best=-1d;
        int limit=Math.min(3,candidates.size());
        for(int i=0;i<limit;i++){
            double verified=matcher.verify(frame,template,candidates.get(i),false,-1);
            if(verified>best)best=verified;
        }
        return best;
    }
}
