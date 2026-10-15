package socket

import (
	"github.com/go-gost/core/logger"
	"github.com/go-gost/x/config"
	"github.com/go-gost/x/config/loader"
	xlogger "github.com/go-gost/x/logger"
	"github.com/go-gost/x/registry"
	"io"
	"net"
	"testing"
)

func TestReconcileRemovesOrphansAndRestoresPausedState(t *testing.T) {
	logger.SetDefault(xlogger.NewLogger(xlogger.OutputOption(io.Discard)))
	config.Set(&config.Config{})
	defer func() {
		for _, name := range []string{"1_1_0_tcp", "2_1_0_tcp", "3_1_0_udp", "web_api"} {
			registry.ServiceRegistry().Unregister(name)
		}
		config.Set(&config.Config{})
	}()
	makeConfig := func(name string) config.ServiceConfig {
		return config.ServiceConfig{Name: name, Addr: "127.0.0.1:0", Listener: &config.ListenerConfig{Type: "tcp"}, Handler: &config.HandlerConfig{Type: "tcp"}}
	}
	orphan, api := makeConfig("3_1_0_udp"), makeConfig("web_api")
	if err := createServices(createServicesRequest{Data: []config.ServiceConfig{orphan, api}}); err != nil {
		t.Fatal(err)
	}
	active, paused := makeConfig("1_1_0_tcp"), makeConfig("2_1_0_tcp")
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	paused.Addr = ln.Addr().String()
	ln.Close()
	paused.Metadata = map[string]any{"paused": true}
	for i := 0; i < 2; i++ {
		if err := reconcileForwards([]config.ServiceConfig{active, paused}); err != nil {
			t.Fatal(err)
		}
		if registry.ServiceRegistry().Get("3_1_0_udp") != nil {
			t.Fatal("orphan survived")
		}
		if registry.ServiceRegistry().Get("web_api") == nil {
			t.Fatal("API removed")
		}
		ln, err = net.Listen("tcp", paused.Addr)
		if err != nil {
			t.Fatal("paused rule is listening", err)
		}
		ln.Close()
	}
	if err := loader.Load(config.Global()); err != nil {
		t.Fatal(err)
	}
	ln, err = net.Listen("tcp", paused.Addr)
	if err != nil {
		t.Fatal("paused rule reopened after loading saved config", err)
	}
	ln.Close()
	if err := reconcileForwards([]config.ServiceConfig{api}); err == nil {
		t.Fatal("accepted unowned service")
	}
}
