FROM python:3.10-slim

# Install system dependencies
# openssh-server: For SSH access, xauth: For X11 forwarding
RUN apt-get update && apt-get install -y \
    openssh-server \
    xauth \
    git \
    curl \
    && rm -rf /var/lib/apt/lists/*

# Setup SSH
RUN mkdir -p /run/sshd
# Hardening SSH: Uncomment and enforce strict settings, enable X11, and configure Keep-Alives
RUN sed -i 's/#PermitRootLogin prohibit-password/PermitRootLogin prohibit-password/' /etc/ssh/sshd_config && \
    sed -i 's/#PasswordAuthentication yes/PasswordAuthentication no/' /etc/ssh/sshd_config && \
    sed -i 's/#PermitEmptyPasswords no/PermitEmptyPasswords no/' /etc/ssh/sshd_config && \
    sed -i 's/#X11Forwarding no/X11Forwarding yes/' /etc/ssh/sshd_config && \
    sed -i 's/#X11UseLocalhost yes/X11UseLocalhost no/' /etc/ssh/sshd_config && \
    echo "TCPKeepAlive yes" >> /etc/ssh/sshd_config && \
    echo "ClientAliveInterval 60" >> /etc/ssh/sshd_config && \
    echo "ClientAliveCountMax 120" >> /etc/ssh/sshd_config
EXPOSE 22

# Default command
CMD ["/usr/sbin/sshd", "-D"]

