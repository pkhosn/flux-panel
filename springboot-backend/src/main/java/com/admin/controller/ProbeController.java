package com.admin.controller;

import com.admin.common.lang.R;
import com.admin.common.utils.JwtUtil;
import com.admin.service.probe.ProbeRegistrationService;
import org.springframework.web.bind.annotation.*;
import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

@RestController
@RequestMapping("/api/v1/probe")
public class ProbeController {
    private final ProbeRegistrationService probes;
    private final com.admin.service.probe.ProbeActivationService activation;
    public ProbeController(ProbeRegistrationService probes, com.admin.service.probe.ProbeActivationService activation) {
        this.probes = probes; this.activation = activation;
    }

    @PostMapping("/activate")
    public R activate(@Valid @RequestBody Activation request) {
        return activation.activate(request.id,JwtUtil.getUserIdFromToken(),request.name,request.ports);
    }

    public static class Activation {
        @javax.validation.constraints.NotNull @javax.validation.constraints.Positive public Long id;
        @NotBlank @Size(max=100) public String name;
        @NotBlank @Size(max=1000) public String ports;
    }

    @PostMapping("/token")
    public R token() {
        return R.ok(java.util.Map.of("token",probes.issue(JwtUtil.getUserIdFromToken()),"expiresIn",900));
    }

    @PostMapping("/list")
    public R list() { return R.ok(probes.listOwned(JwtUtil.getUserIdFromToken())); }

    // No request logging: bearer registration tokens and returned node secrets are credentials.
    @PostMapping("/register")
    public R register(@Valid @RequestBody Registration request) {
        try { return R.ok(probes.register(request.token,request.name)); }
        catch (IllegalArgumentException invalid) { return R.err(invalid.getMessage()); }
    }

    public static class Registration {
        @NotBlank @Size(min=43,max=43) public String token;
        @NotBlank @Size(max=100) public String name;
    }
}
