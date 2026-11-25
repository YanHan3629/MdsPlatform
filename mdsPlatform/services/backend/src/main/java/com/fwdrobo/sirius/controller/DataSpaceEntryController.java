package com.fwdrobo.sirius.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class DataSpaceEntryController {
    @GetMapping({"/", "/index.html"})
    public String dataSpaceEntry() {
        return "redirect:/data-space/index.html";
    }
}
