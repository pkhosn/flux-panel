package socket

import (
	"fmt"
	"io"
	"net"
	"testing"

	"github.com/go-gost/core/logger"
	"github.com/go-gost/x/config"
	_ "github.com/go-gost/x/connector/http"
	_ "github.com/go-gost/x/dialer/tcp"
	_ "github.com/go-gost/x/handler/forward/local"
	_ "github.com/go-gost/x/listener/tcp"
	xlogger "github.com/go-gost/x/logger"
	"github.com/go-gost/x/registry"
)

func TestCreateServicesClosesParsedListenersOnFailure(t *testing.T) {
	logger.SetDefault(xlogger.NewLogger(xlogger.OutputOption(io.Discard)))
	held, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	port := held.Addr().(*net.TCPAddr).Port
	held.Close()
	addr := net.JoinHostPort("127.0.0.1", fmt.Sprint(port))
	configure := func(name, address string) config.ServiceConfig {
		return config.ServiceConfig{
			Name: name, Addr: address,
			Listener:  &config.ListenerConfig{Type: "tcp"},
			Handler:   &config.HandlerConfig{Type: "tcp"},
			Forwarder: &config.ForwarderConfig{Nodes: []*config.ForwardNodeConfig{{Name: "target", Addr: "127.0.0.1:9"}}},
		}
	}
	err = createServices(createServicesRequest{Data: []config.ServiceConfig{
		configure("test-first", addr), configure("test-second", "invalid-address"),
	}})
	if err == nil {
		t.Fatal("invalid second service was accepted")
	}
	if registry.ServiceRegistry().IsRegistered("test-first") {
		t.Fatal("first service remained registered")
	}
	rebound, err := net.Listen("tcp", addr)
	if err != nil {
		t.Fatalf("first service leaked listener: %v", err)
	}
	rebound.Close()
}
