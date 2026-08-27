package com.sportshop.shared.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class SpaForwardController {
    @GetMapping({
            "/", "/catalog", "/inbounds", "/inbounds/history", "/inventory",
            "/sales", "/sales/history", "/returns", "/reports", "/settings"
    })
    public String applicationRoute() {
        return "forward:/index.html";
    }

    @GetMapping("/sales/{id:[0-9a-fA-F-]{36}}")
    public String saleDetailRoute() {
        return "forward:/index.html";
    }
}
