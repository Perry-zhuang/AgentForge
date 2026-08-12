package com.example.full.controller;

import com.example.full.mapper.UserMapper;
import com.example.full.service.UserService;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class UserController {
    private final UserService service;
    private final UserMapper mapper;
    public UserController(UserService service, UserMapper mapper) { this.service = service; this.mapper = mapper; }
}
