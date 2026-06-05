package com.paytm.disburse.channel;

import com.paytm.disburse.domain.Channel;
import java.util.List;

public interface ChannelRouter {
    List<Channel> routeFor(long amountPaise);
}
