package com.paytm.disburse.channel;

import com.paytm.disburse.domain.Channel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Set;
import java.util.function.Supplier;

@Configuration
public class RouterConfig {

    @Bean
    public Supplier<Set<Channel>> openCircuits() {
        return Set::of;
    }

    @Bean
    public ChannelRouter channelRouter(Supplier<Set<Channel>> openCircuits) {
        return new AmountTieredChannelRouter(openCircuits);
    }
}
