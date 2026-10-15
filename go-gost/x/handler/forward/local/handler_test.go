package local

import (
	"io"
	"net"
	"testing"
	"time"

	"github.com/go-gost/core/logger"
	"github.com/go-gost/x/config"
	serviceparser "github.com/go-gost/x/config/parsing/service"
	_ "github.com/go-gost/x/connector/http"
	_ "github.com/go-gost/x/dialer/tcp"
	_ "github.com/go-gost/x/listener/tcp"
	xlogger "github.com/go-gost/x/logger"
)

func TestTCPForwardProxyHeader(t *testing.T) {
	logger.SetDefault(xlogger.NewLogger(xlogger.OutputOption(io.Discard)))
	target, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer target.Close()

	for _, version := range []int{0, 1, 2} {
		cfg := &config.ServiceConfig{
			Name: "proxy-test", Addr: "127.0.0.1:0",
			Listener:  &config.ListenerConfig{Type: "tcp"},
			Handler:   &config.HandlerConfig{Type: "tcp", Metadata: map[string]any{"proxyProtocol": version}},
			Forwarder: &config.ForwarderConfig{Nodes: []*config.ForwardNodeConfig{{Name: "target", Addr: target.Addr().String()}}},
		}
		svc, err := serviceparser.ParseService(cfg)
		if err != nil {
			t.Fatal(err)
		}
		done := make(chan error, 1)
		go func() { done <- svc.Serve() }()
		client, err := net.DialTimeout("tcp", svc.Addr().String(), time.Second)
		if err != nil {
			svc.Close()
			t.Fatal(err)
		}
		client.SetDeadline(time.Now().Add(2 * time.Second))
		if _, err = client.Write([]byte("hello")); err != nil {
			t.Fatal(err)
		}
		upstream, err := target.Accept()
		if err != nil {
			t.Fatal(err)
		}
		upstream.SetDeadline(time.Now().Add(2 * time.Second))
		prefix := make([]byte, 5)
		if _, err = io.ReadFull(upstream, prefix); err != nil {
			t.Fatal(err)
		}
		if version == 0 && string(prefix) != "hello" {
			t.Fatalf("default forward changed: %q", prefix)
		}
		if version == 1 && string(prefix) != "PROXY" {
			t.Fatalf("v1 header missing: %q", prefix)
		}
		if version == 2 && string(prefix) != "\r\n\r\n\x00" {
			t.Fatalf("v2 header missing: %q", prefix)
		}
		upstream.Close()
		client.Close()
		svc.Close()
		select {
		case <-done:
		case <-time.After(2 * time.Second):
			t.Fatal("forward service did not stop")
		}
	}
}
