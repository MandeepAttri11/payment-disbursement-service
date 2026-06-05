package com.paytm.disburse.api;

import com.paytm.disburse.service.ReconciliationService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class ReconciliationController {

    private final ReconciliationService svc;

    public ReconciliationController(ReconciliationService svc) { this.svc = svc; }

    @PostMapping("/reconcile")
    public ReconciliationService.Report reconcile(@RequestParam("file") MultipartFile file) throws Exception {
        return svc.reconcile(file.getInputStream());
    }
}
