package com.example.pakredirect;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public final class ScriptStartupTermsTest {
    private static final int WIDTH = 1600;
    private static final int HEIGHT = 900;

    @Test public void acceptsOnlyTheKnownTermsPageAndUsesAgreeOcrCenter() {
        ScriptStartupTerms.Tap tap = ScriptStartupTerms.findAgreeTap(knownPage(), WIDTH, HEIGHT);
        assertNotNull(tap);
        assertEquals(602, tap.x);
        assertEquals(748, tap.y);
    }

    @Test public void rejectsGenericDialogAndIncompleteTermsBody() {
        assertNull(ScriptStartupTerms.findAgreeTap(Arrays.asList(
                line("提示", 800, 240), line("是否继续游戏？", 800, 470),
                line("同意", 602, 748), line("退出", 998, 748)), WIDTH, HEIGHT));
        List<ScriptStartupTerms.Line> incomplete = knownPage();
        incomplete.remove(2);
        assertNull(ScriptStartupTerms.findAgreeTap(incomplete, WIDTH, HEIGHT));
    }

    @Test public void rejectsExitPartialAgreeAndUnexpectedButtonPositions() {
        List<ScriptStartupTerms.Line> exitOnly = knownPage();
        exitOnly.remove(3);
        assertNull(ScriptStartupTerms.findAgreeTap(exitOnly, WIDTH, HEIGHT));

        List<ScriptStartupTerms.Line> partialAgree = knownPage();
        partialAgree.set(3, line("同意并继续", 602, 748));
        assertNull(ScriptStartupTerms.findAgreeTap(partialAgree, WIDTH, HEIGHT));

        List<ScriptStartupTerms.Line> misplaced = knownPage();
        misplaced.set(3, line("同意", 998, 748));
        assertNull(ScriptStartupTerms.findAgreeTap(misplaced, WIDTH, HEIGHT));
    }

    @Test public void rejectsAmbiguousAgreeButtons() {
        List<ScriptStartupTerms.Line> ambiguous = knownPage();
        ambiguous.add(line("同意", 650, 752));
        assertNull(ScriptStartupTerms.findAgreeTap(ambiguous, WIDTH, HEIGHT));
    }

    private static List<ScriptStartupTerms.Line> knownPage() {
        return new ArrayList<>(Arrays.asList(
                line("规则和使用条款", 800, 240),
                line("参与游戏时，除了与Tepaylink发行商的服务协议外，客户", 800, 475),
                line("已默认接受以下规定：", 500, 540),
                line("同意", 602, 748),
                line("退出", 998, 748)));
    }

    private static ScriptStartupTerms.Line line(String text, int x, int y) {
        return new ScriptStartupTerms.Line(text, x, y);
    }
}
