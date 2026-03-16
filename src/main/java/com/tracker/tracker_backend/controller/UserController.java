package com.tracker.tracker_backend.controller;

import com.tracker.tracker_backend.dto.CreateUserRequest;
import com.tracker.tracker_backend.dto.UpdateLocationRequest;
import com.tracker.tracker_backend.dto.UserResponse;
import com.tracker.tracker_backend.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse register(@Valid @RequestBody CreateUserRequest req) {
        return userService.createUser(req);
    }

    @PutMapping("/{id}/location")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateLocation(@PathVariable UUID id,
                               @Valid @RequestBody UpdateLocationRequest req) {
        userService.updateLocation(id, req);
    }

    @PatchMapping("/{id}/active")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setActive(@PathVariable UUID id,
                          @RequestParam boolean active) {
        userService.setActive(id, active);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        userService.deleteUser(id);
    }
}
