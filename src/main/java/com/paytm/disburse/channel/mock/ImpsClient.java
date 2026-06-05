package com.paytm.disburse.channel.mock;

import com.paytm.disburse.domain.Channel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Random;

@Component
public class ImpsClient extends MockChannelBase {
    public ImpsClient(@Qualifier("impsProps") MockChannelProperties props,
                      @Qualifier("channelRandom") Random random) {
        super(props, random);
    }
    @Override public Channel channel() { return Channel.IMPS; }
}
