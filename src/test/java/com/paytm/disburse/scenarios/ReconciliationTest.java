package com.paytm.disburse.scenarios;

import com.paytm.disburse.channel.ChannelResponse;
import com.paytm.disburse.service.CreateDisbursementCommand;
import com.paytm.disburse.service.DisbursementService;
import com.paytm.disburse.service.ReconciliationService;
import com.paytm.disburse.support.ControllableChannelClient;
import com.paytm.disburse.support.TestChannels;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestChannels.class)
class ReconciliationTest {

    @Autowired DisbursementService service;
    @Autowired ReconciliationService recon;
    @Autowired ControllableChannelClient impsClient;

    @Test
    void internal_success_but_no_bank_row_creates_INTERNAL_ONLY_break() throws Exception {
        impsClient.enqueue(ChannelResponse.success("ok"));
        var d = service.create(new CreateDisbursementCommand("L-RECON",
            "1234", "HDFC0001234", null, 200_000_00L, null, null));
        service.processAttempt(d.id());

        String csv = "bank_reference_id,transaction_date,amount_paise,account,ifsc,status\n";
        var report = recon.reconcile(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));

        assertThat(report.breaks()).anyMatch(b -> "INTERNAL_ONLY".equals(b.type()));
    }
}
