package com.example.pakredirect.vision;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Conservative numeric OCR post-processor. Ambiguous or out-of-range reads stay UNKNOWN. */
public final class DigitVision {
    public enum Status { CONFIRMED, UNKNOWN }
    public static final class Reading {
        public final Status status;
        public final Integer value;
        public final double confidence;
        public final String raw;
        Reading(Status status,Integer value,double confidence,String raw){
            this.status=status;this.value=value;this.confidence=confidence;this.raw=raw==null?"":raw;
        }
    }
    private static final Pattern DIGITS = Pattern.compile("(?<![0-9])[0-9]{1,9}(?![0-9])");
    private DigitVision(){}

    public static Reading parse(String raw,double confidence,int minimum,int maximum,double requiredConfidence){
        if(raw==null || !Double.isFinite(confidence) || confidence<requiredConfidence || minimum>maximum)
            return unknown(raw,confidence);
        String normalized=raw.replace('O','0').replace('o','0').replace('I','1').replace('l','1')
                .replace('｜','1').replace('|','1').trim();
        Matcher matcher=DIGITS.matcher(normalized);
        List<Integer> numbers=new ArrayList<>();
        while(matcher.find()){
            try{numbers.add(Integer.parseInt(matcher.group()));}catch(NumberFormatException ignored){}
        }
        if(numbers.size()!=1)return unknown(raw,confidence);
        int value=numbers.get(0);
        if(value<minimum||value>maximum)return unknown(raw,confidence);
        return new Reading(Status.CONFIRMED,value,confidence,raw);
    }

    public static Reading agree(Reading first,Reading second,double minimumConfidence){
        if(first==null||second==null||first.status!=Status.CONFIRMED||second.status!=Status.CONFIRMED
                ||first.value==null||!first.value.equals(second.value)
                ||first.confidence<minimumConfidence||second.confidence<minimumConfidence)
            return unknown((first==null?"":first.raw)+" | "+(second==null?"":second.raw),0);
        return new Reading(Status.CONFIRMED,first.value,
                Math.min(first.confidence,second.confidence),first.raw+" | "+second.raw);
    }

    private static Reading unknown(String raw,double confidence){
        return new Reading(Status.UNKNOWN,null,Double.isFinite(confidence)?confidence:0d,raw);
    }
}
