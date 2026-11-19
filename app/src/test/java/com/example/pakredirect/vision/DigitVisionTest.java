package com.example.pakredirect.vision;
import static org.junit.Assert.*;
import org.junit.Test;

public class DigitVisionTest {
    @Test public void acceptsOneConfidentInRangeNumber() {
        DigitVision.Reading r=DigitVision.parse(" 15 ",0.96,0,35,0.9);
        assertEquals(DigitVision.Status.CONFIRMED,r.status);
        assertEquals(Integer.valueOf(15),r.value);
    }
    @Test public void rejectsAmbiguousOutOfRangeAndLowConfidence() {
        assertEquals(DigitVision.Status.UNKNOWN,DigitVision.parse("15 16",0.99,0,35,0.9).status);
        assertEquals(DigitVision.Status.UNKNOWN,DigitVision.parse("99",0.99,0,35,0.9).status);
        assertEquals(DigitVision.Status.UNKNOWN,DigitVision.parse("15",0.5,0,35,0.9).status);
        assertEquals(DigitVision.Status.UNKNOWN,DigitVision.parse("",0.99,0,35,0.9).status);
    }
    @Test public void requiresTwoReadingsToAgree() {
        DigitVision.Reading a=DigitVision.parse("12",0.99,0,99,0.9);
        DigitVision.Reading b=DigitVision.parse("12",0.95,0,99,0.9);
        DigitVision.Reading c=DigitVision.parse("13",0.99,0,99,0.9);
        assertEquals(DigitVision.Status.CONFIRMED,DigitVision.agree(a,b,0.9).status);
        assertEquals(DigitVision.Status.UNKNOWN,DigitVision.agree(a,c,0.9).status);
    }
}
