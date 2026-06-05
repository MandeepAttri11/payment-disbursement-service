package com.paytm.disburse.channel;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class RouterConfig {
    @Bean @Primary
    public ChannelRouter channelRouter(CircuitBreakerChannelGuard guard) {
        return new AmountTieredChannelRouter(guard);
    }
}
