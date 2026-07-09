package com.fwdrobo.sirius.docker.entity;

import com.fwdrobo.sirius.container.ContainerProperties;
import org.springframework.stereotype.Component;

@Component
public class DockerHostResolver {

    private final ContainerProperties props;

    public DockerHostResolver(ContainerProperties props) {
        this.props = props;
    }

    public String resolve(String server) {
        if (props.getServers() == null || props.getServers().isEmpty()) {
            throw new IllegalStateException("app.docker.servers is empty or missing in config");
        }

        String v = props.getServers().get(server);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("Docker server not configured for " + server
                    + ". Check app.docker.servers in application.yml");
        }
        return v;
    }
}
