package proxyproto

import (
	"fmt"
	"net"
	"time"

	proxyproto "github.com/pires/go-proxyproto"
)

func WrapListener(ppv int, ln net.Listener, readHeaderTimeout time.Duration) net.Listener {
	if ppv <= 0 {
		return ln
	}

	return &proxyproto.Listener{
		Listener:          ln,
		ReadHeaderTimeout: readHeaderTimeout,
	}
}

func WrapListenerTrusted(ppv int, ln net.Listener, readHeaderTimeout time.Duration, trusted []*net.IPNet) net.Listener {
	if ppv <= 0 {
		return ln
	}
	return &proxyproto.Listener{
		Listener:          ln,
		ReadHeaderTimeout: readHeaderTimeout,
		Policy: func(addr net.Addr) (proxyproto.Policy, error) {
			host, _, err := net.SplitHostPort(addr.String())
			if err != nil {
				return proxyproto.SKIP, fmt.Errorf("invalid proxy peer address: %w", err)
			}
			ip := net.ParseIP(host)
			if ip == nil {
				return proxyproto.SKIP, fmt.Errorf("invalid proxy peer IP: %s", host)
			}
			for _, network := range trusted {
				if network.Contains(ip) {
					return proxyproto.REQUIRE, nil
				}
			}
			return proxyproto.SKIP, fmt.Errorf("untrusted proxy peer: %s", host)
		},
	}
}
