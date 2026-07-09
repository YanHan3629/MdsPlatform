package com.fwdrobo.sirius.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/docker")
public class DockerController {

    //todo 修改为从系统内部 container hub 获取 image
    @GetMapping("/images")
    public Map<String, Object> getImages() {
        List<String> images = Arrays.asList("yrwang1o1/rosetta:20260316", "yrwang1o1/gr00t_n1.5:20260316");
        return Map.of("status", true,
                "images", images);
    }

}
