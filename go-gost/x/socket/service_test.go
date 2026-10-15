package socket

import (
	"fmt"
	"io"
	"net"
	"testing"
	"time"

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

func TestPauseFailureRestoresEarlierService(t *testing.T) {
	logger.SetDefault(xlogger.NewLogger(xlogger.OutputOption(io.Discard)))
	config.Set(&config.Config{})
	held, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	addr := held.Addr().String()
	held.Close()
	serviceConfig := config.ServiceConfig{
		Name: "pause-first", Addr: addr,
		Listener:  &config.ListenerConfig{Type: "tcp"},
		Handler:   &config.HandlerConfig{Type: "tcp"},
		Forwarder: &config.ForwarderConfig{Nodes: []*config.ForwardNodeConfig{{Name: "target", Addr: "127.0.0.1:9"}}},
	}
	if err := createServices(createServicesRequest{Data: []config.ServiceConfig{serviceConfig}}); err != nil {
		t.Fatal(err)
	}
	defer func() {
		registry.ServiceRegistry().Unregister("pause-first")
		config.Set(&config.Config{})
	}()
	if err := pauseServices(pauseServicesRequest{Services: []string{"pause-first", "missing-service"}}); err == nil {
		t.Fatal("missing service was accepted")
	}
	// Force a failure after the first service closes by removing only the second config.
	second := serviceConfig
	second.Name = "pause-second"
	second.Addr = "127.0.0.1:0"
	if err := createServices(createServicesRequest{Data: []config.ServiceConfig{second}}); err != nil {
		t.Fatal(err)
	}
	defer registry.ServiceRegistry().Unregister("pause-second")
	config.OnUpdate(func(c *config.Config) error {
		for i, entry := range c.Services {
			if entry.Name == "pause-second" {
				c.Services = append(c.Services[:i], c.Services[i+1:]...)
				break
			}
		}
		return nil
	})
	if err := pauseServices(pauseServicesRequest{Services: []string{"pause-first", "pause-second"}}); err == nil {
		t.Fatal("missing second config was accepted")
	}
	if registry.ServiceRegistry().Get("pause-first") == nil {
		t.Fatal("first service disappeared during rollback")
	}
	conn, err := net.DialTimeout("tcp", addr, time.Second)
	if err != nil {
		t.Fatalf("first service did not restart: %v", err)
	}
	conn.Close()
}
