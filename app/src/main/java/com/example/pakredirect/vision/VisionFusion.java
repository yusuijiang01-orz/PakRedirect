package com.example.pakredirect.vision;

/** Cross-signal and cross-frame fusion. Contradictory evidence is UNKNOWN. */
public final class VisionFusion {
    public static final class Result {
        public final String label;
        public final double confidence;
        public final boolean confirmed;
        Result(String label,double confidence,boolean confirmed){
            this.label=label;this.confidence=confidence;this.confirmed=confirmed;
        }
    }
    private VisionFusion(){}

    public static Result agreeFrames(String first,double firstConfidence,String second,
                                     double secondConfidence,double minimum,double minimumMargin){
        if(first==null||second==null||first.isEmpty()||!first.equals(second)
                ||!Double.isFinite(firstConfidence)||!Double.isFinite(secondConfidence)
                ||firstConfidence<minimum||secondConfidence<minimum
                ||Math.min(firstConfidence,secondConfidence)<Math.max(minimum,minimumMargin))
            return new Result("UNKNOWN",0d,false);
        return new Result(first,Math.min(firstConfidence,secondConfidence),true);
    }

    /** At least two independent channels must agree before returning a label. */
    public static Result agreeSignals(String templateLabel,double templateConfidence,
                                      String colorLabel,double colorConfidence,
                                      String textLabel,double textConfidence,double minimum){
        String winner=null;int votes=0;double confidence=1d;
        String[] labels={templateLabel,colorLabel,textLabel};
        double[] scores={templateConfidence,colorConfidence,textConfidence};
        for(int i=0;i<labels.length;i++){
            if(labels[i]==null||labels[i].isEmpty()||"UNKNOWN".equals(labels[i])
                    ||!Double.isFinite(scores[i])||scores[i]<minimum)continue;
            int count=0;double weakest=scores[i];
            for(int j=0;j<labels.length;j++){
                if(labels[i].equals(labels[j])&&Double.isFinite(scores[j])&&scores[j]>=minimum){
                    count++;weakest=Math.min(weakest,scores[j]);
                }
            }
            if(count>votes){votes=count;winner=labels[i];confidence=weakest;}
        }
        return votes>=2?new Result(winner,confidence,true):new Result("UNKNOWN",0d,false);
    }
}
