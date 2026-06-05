package com.paytm.disburse.channel.mock;

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.Random;

@Configuration
public class MockChannelConfig {

    @Bean Random channelRandom() { return new Random(); }

    @Bean MockChannelProperties upiProps(Environment env) { return bind(env, "disburse.channels.upi"); }
    @Bean MockChannelProperties impsProps(Environment env) { return bind(env, "disburse.channels.imps"); }
    @Bean MockChannelProperties neftProps(Environment env) { return bind(env, "disburse.channels.neft"); }

    private MockChannelProperties bind(Environment env, String prefix) {
        return Binder.get(env).bindOrCreate(prefix, MockChannelProperties.class);
    }
}
