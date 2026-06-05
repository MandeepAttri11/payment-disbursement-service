package com.paytm.disburse.channel;

import com.paytm.disburse.domain.Channel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ChannelRouterTest {

    @Test
    void small_amounts_try_upi_first_then_imps_then_neft() {
        ChannelRouter r = new AmountTieredChannelRouter(() -> Set.of());
        assertThat(r.routeFor(50_000_00L)).containsExactly(Channel.UPI, Channel.IMPS, Channel.NEFT);
    }

    @Test
    void medium_amounts_skip_upi_for_imps_then_neft() {
        ChannelRouter r = new AmountTieredChannelRouter(() -> Set.of());
        assertThat(r.routeFor(200_000_00L)).containsExactly(Channel.IMPS, Channel.NEFT);
    }

    @Test
    void large_amounts_only_neft() {
        ChannelRouter r = new AmountTieredChannelRouter(() -> Set.of());
        assertThat(r.routeFor(10_000_000_00L)).containsExactly(Channel.NEFT);
    }

    @Test
    void open_circuit_excludes_channel() {
        ChannelRouter r = new AmountTieredChannelRouter(() -> Set.of(Channel.UPI));
        assertThat(r.routeFor(50_000_00L)).containsExactly(Channel.IMPS, Channel.NEFT);
    }

    @Test
    void all_circuits_open_returns_empty_list() {
        ChannelRouter r = new AmountTieredChannelRouter(
            () -> Set.of(Channel.UPI, Channel.IMPS, Channel.NEFT));
        assertThat(r.routeFor(50_000_00L)).isEmpty();
    }
}
