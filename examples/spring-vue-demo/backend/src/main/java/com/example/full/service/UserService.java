package com.example.full.service;

import com.example.full.mapper.UserMapper;
import org.springframework.stereotype.Service;

@Service
public class UserService {
    private final UserMapper mapper;
    public UserService(UserMapper mapper) { this.mapper = mapper; }
}
