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

func TestUpdateServicesRestoresListenersOnParseFailure(t *testing.T) {
	logger.SetDefault(xlogger.NewLogger(xlogger.OutputOption(io.Discard)))
	config.Set(&config.Config{})
	makeConfig := func(name string) config.ServiceConfig {
		return config.ServiceConfig{
			Name: name, Addr: "127.0.0.1:0",
			Listener:  &config.ListenerConfig{Type: "tcp"},
			Handler:   &config.HandlerConfig{Type: "tcp"},
			Forwarder: &config.ForwarderConfig{Nodes: []*config.ForwardNodeConfig{{Name: "target", Addr: "127.0.0.1:9"}}},
		}
	}
	first := makeConfig("update-first")
	second := makeConfig("update-second")
	held, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	first.Addr = held.Addr().String()
	held.Close()
	if err := createServices(createServicesRequest{Data: []config.ServiceConfig{first, second}}); err != nil {
		t.Fatal(err)
	}
	defer func() {
		registry.ServiceRegistry().Unregister(first.Name)
		registry.ServiceRegistry().Unregister(second.Name)
		config.Set(&config.Config{})
	}()
	changed := first
	changed.Forwarder = &config.ForwarderConfig{Nodes: []*config.ForwardNodeConfig{{Name: "new", Addr: "127.0.0.1:10"}}}
	invalid := second
	invalid.Addr = "invalid-address"
	if err := updateServices(updateServicesRequest{Data: []config.ServiceConfig{changed, invalid}}); err == nil {
		t.Fatal("invalid second service was accepted")
	}
	for _, original := range []config.ServiceConfig{first, second} {
		if registry.ServiceRegistry().Get(original.Name) == nil {
			t.Fatalf("%s disappeared during rollback", original.Name)
		}
		var stored *config.ServiceConfig
		for _, entry := range config.Global().Services {
			if entry.Name == original.Name {
				stored = entry
			}
		}
		if stored == nil || stored.Forwarder.Nodes[0].Addr != original.Forwarder.Nodes[0].Addr {
			t.Fatalf("%s config changed after failed update", original.Name)
		}
	}
	conn, err := net.DialTimeout("tcp", first.Addr, time.Second)
	if err != nil {
		t.Fatalf("original listener was not restored: %v", err)
	}
	conn.Close()
}

func TestUpdateServicesReplacesConfigAndListener(t *testing.T) {
	logger.SetDefault(xlogger.NewLogger(xlogger.OutputOption(io.Discard)))
	config.Set(&config.Config{})
	original := config.ServiceConfig{
		Name: "update-success", Addr: "127.0.0.1:0",
		Listener:  &config.ListenerConfig{Type: "tcp"},
		Handler:   &config.HandlerConfig{Type: "tcp"},
		Forwarder: &config.ForwarderConfig{Nodes: []*config.ForwardNodeConfig{{Name: "old", Addr: "127.0.0.1:9"}}},
	}
	if err := createServices(createServicesRequest{Data: []config.ServiceConfig{original}}); err != nil {
		t.Fatal(err)
	}
	defer func() {
		registry.ServiceRegistry().Unregister(original.Name)
		config.Set(&config.Config{})
	}()
	held, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	newAddr := held.Addr().String()
	held.Close()
	updated := original
	updated.Addr = newAddr
	updated.Forwarder = &config.ForwarderConfig{Nodes: []*config.ForwardNodeConfig{{Name: "new", Addr: "127.0.0.1:10"}}}
	if err := updateServices(updateServicesRequest{Data: []config.ServiceConfig{updated}}); err != nil {
		t.Fatal(err)
	}
	stored := config.Global().Services
	if len(stored) != 1 || stored[0].Addr != newAddr || stored[0].Forwarder.Nodes[0].Addr != "127.0.0.1:10" {
		t.Fatal("updated service config was not stored")
	}
	conn, err := net.DialTimeout("tcp", newAddr, time.Second)
	if err != nil {
		t.Fatalf("updated listener was not started: %v", err)
	}
	conn.Close()
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

func TestResumeFailureKeepsPausedServicesRegistered(t *testing.T) {
	logger.SetDefault(xlogger.NewLogger(xlogger.OutputOption(io.Discard)))
	config.Set(&config.Config{})
	makeConfig := func(name string) config.ServiceConfig {
		return config.ServiceConfig{
			Name: name, Addr: "127.0.0.1:0",
			Listener:  &config.ListenerConfig{Type: "tcp"},
			Handler:   &config.HandlerConfig{Type: "tcp"},
			Forwarder: &config.ForwarderConfig{Nodes: []*config.ForwardNodeConfig{{Name: "target", Addr: "127.0.0.1:9"}}},
		}
	}
	if err := createServices(createServicesRequest{Data: []config.ServiceConfig{makeConfig("resume-first"), makeConfig("resume-second")}}); err != nil {
		t.Fatal(err)
	}
	defer func() {
		registry.ServiceRegistry().Unregister("resume-first")
		registry.ServiceRegistry().Unregister("resume-second")
		config.Set(&config.Config{})
	}()
	if err := pauseServices(pauseServicesRequest{Services: []string{"resume-first", "resume-second"}}); err != nil {
		t.Fatal(err)
	}
	config.OnUpdate(func(c *config.Config) error {
		for _, entry := range c.Services {
			if entry.Name == "resume-second" {
				entry.Addr = "invalid-address"
			}
		}
		return nil
	})
	if err := resumeServices(resumeServicesRequest{Services: []string{"resume-first", "resume-second"}}); err == nil {
		t.Fatal("invalid second service was resumed")
	}
	for _, name := range []string{"resume-first", "resume-second"} {
		if registry.ServiceRegistry().Get(name) == nil {
			t.Fatalf("%s disappeared after rollback", name)
		}
	}
}
