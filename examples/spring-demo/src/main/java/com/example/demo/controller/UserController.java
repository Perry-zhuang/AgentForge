package com.example.demo.controller;

import com.example.demo.service.UserService;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class UserController {
    private final UserService userService;
    public UserController(UserService userService) { this.userService = userService; }
}
