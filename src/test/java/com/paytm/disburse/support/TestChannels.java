package com.paytm.disburse.support;

import com.paytm.disburse.domain.Channel;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@TestConfiguration
public class TestChannels {

    @Bean(name = "upiClient")  public ControllableChannelClient upi()  { return new ControllableChannelClient(Channel.UPI); }
    @Bean(name = "impsClient") public ControllableChannelClient imps() { return new ControllableChannelClient(Channel.IMPS); }
    @Bean(name = "neftClient") public ControllableChannelClient neft() { return new ControllableChannelClient(Channel.NEFT); }
}
