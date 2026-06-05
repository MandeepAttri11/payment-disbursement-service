package com.paytm.disburse.channel;

import com.paytm.disburse.domain.Channel;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

public class AmountTieredChannelRouter implements ChannelRouter {

    private static final long UPI_MAX  = 100_000_00L;
    private static final long IMPS_MAX = 500_000_00L;

    private final Supplier<Set<Channel>> openCircuits;

    public AmountTieredChannelRouter(Supplier<Set<Channel>> openCircuits) {
        this.openCircuits = openCircuits;
    }

    @Override
    public List<Channel> routeFor(long amountPaise) {
        Set<Channel> blocked = openCircuits.get();
        List<Channel> all = new ArrayList<>();
        if (amountPaise <= UPI_MAX)  all.add(Channel.UPI);
        if (amountPaise <= IMPS_MAX) all.add(Channel.IMPS);
        all.add(Channel.NEFT);
        all.removeAll(blocked);
        return all;
    }
}
