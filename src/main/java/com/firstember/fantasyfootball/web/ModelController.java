package com.firstember.fantasyfootball.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class ModelController {

    @GetMapping("/model")
    public String about() {
        return "model/about";
    }
}
