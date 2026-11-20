package com.example.pakredirect;

/** Classifies recoverable OCR uncertainty without depending on localized error wording order. */
final class CoraFlowOcrUncertaintyPolicy {
    private CoraFlowOcrUncertaintyPolicy() { }

    static boolean isUncertain(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message == null) continue;
            if (message.contains("OCR 置信度")
                    || message.contains("OCR 没有返回结果")
                    || message.contains("药瓶图标候选")
                    || message.contains("OCR 识别超时")
                    || message.contains("OCR 连续超时")
                    || message.contains("OCR 连续无响应")
                    || message.contains("背包标题未确认")
                    || message.contains("物品详情关闭两次")
                    || message.contains("数量 OCR 不可靠")
                    || message.contains("数量多帧一致性未通过")
                    || message.contains("药瓶数量无法可靠识别")
                    || message.contains("药瓶识别不确定")
                    || message.contains("物品详情遮挡背包")
                    || message.contains("药品数量未确认")) {
                return true;
            }
        }
        return false;
    }
}
