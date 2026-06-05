package com.paytm.disburse.channel.mock;

public class MockChannelProperties {
    private double successRate = 0.94;
    private double timeoutRate = 0.03;
    private long meanLatencyMs = 200;
    private long maxAmountPaise = Long.MAX_VALUE;

    public double getSuccessRate() { return successRate; }
    public void setSuccessRate(double v) { this.successRate = v; }
    public double getTimeoutRate() { return timeoutRate; }
    public void setTimeoutRate(double v) { this.timeoutRate = v; }
    public long getMeanLatencyMs() { return meanLatencyMs; }
    public void setMeanLatencyMs(long v) { this.meanLatencyMs = v; }
    public long getMaxAmountPaise() { return maxAmountPaise; }
    public void setMaxAmountPaise(long v) { this.maxAmountPaise = v; }
}
