package com.paytm.disburse.channel;

import com.paytm.disburse.domain.Channel;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class ChannelClientRegistry {
    private final Map<Channel, ChannelClient> byChannel;

    public ChannelClientRegistry(List<ChannelClient> clients) {
        this.byChannel = clients.stream().collect(Collectors.toMap(ChannelClient::channel, Function.identity()));
    }

    public ChannelClient get(Channel ch) {
        ChannelClient c = byChannel.get(ch);
        if (c == null) throw new IllegalStateException("No client for channel " + ch);
        return c;
    }
}
