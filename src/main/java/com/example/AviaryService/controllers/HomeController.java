package com.example.AviaryService.controllers;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

// The bare site address. Signed-out visitors never reach this: Spring
// Security sends them to /login first. Signed-in owners go to their
// dashboard instead of a 404 "No static resource" error page.
@Controller
public class HomeController {

    @GetMapping("/")
    public String home() {
        return "redirect:/dashboard";
    }
}
