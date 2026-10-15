package local

import (
	"bufio"
	"crypto/tls"
	"encoding/pem"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/go-gost/core/logger"
	"github.com/go-gost/x/config"
	serviceparser "github.com/go-gost/x/config/parsing/service"
	_ "github.com/go-gost/x/connector/http"
	_ "github.com/go-gost/x/dialer/tcp"
	_ "github.com/go-gost/x/listener/tcp"
	_ "github.com/go-gost/x/listener/udp"
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

func TestLegacyTCPAndUDPForwardSamePort(t *testing.T) {
	logger.SetDefault(xlogger.NewLogger(xlogger.OutputOption(io.Discard)))
	tcpTarget, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer tcpTarget.Close()
	udpTarget, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer udpTarget.Close()

	tcpCfg := &config.ServiceConfig{
		Name: "legacy_tcp", Addr: "127.0.0.1:0",
		Listener:  &config.ListenerConfig{Type: "tcp"},
		Handler:   &config.HandlerConfig{Type: "tcp"},
		Forwarder: &config.ForwarderConfig{Nodes: []*config.ForwardNodeConfig{{Name: "target", Addr: tcpTarget.Addr().String()}}},
	}
	tcpSvc, err := serviceparser.ParseService(tcpCfg)
	if err != nil {
		t.Fatal(err)
	}
	defer tcpSvc.Close()
	port := tcpSvc.Addr().(*net.TCPAddr).Port
	udpCfg := &config.ServiceConfig{
		Name: "legacy_udp", Addr: net.JoinHostPort("127.0.0.1", fmt.Sprint(port)),
		Listener:  &config.ListenerConfig{Type: "udp", Metadata: map[string]any{"keepAlive": true}},
		Handler:   &config.HandlerConfig{Type: "udp"},
		Forwarder: &config.ForwarderConfig{Nodes: []*config.ForwardNodeConfig{{Name: "target", Addr: udpTarget.LocalAddr().String()}}},
	}
	udpSvc, err := serviceparser.ParseService(udpCfg)
	if err != nil {
		t.Fatal(err)
	}
	defer udpSvc.Close()
	go tcpSvc.Serve()
	go udpSvc.Serve()

	tcpClient, err := net.DialTimeout("tcp", tcpSvc.Addr().String(), time.Second)
	if err != nil {
		t.Fatal(err)
	}
	defer tcpClient.Close()
	tcpClient.Write([]byte("tcp"))
	upstream, err := tcpTarget.Accept()
	if err != nil {
		t.Fatal(err)
	}
	defer upstream.Close()
	upstream.SetReadDeadline(time.Now().Add(time.Second))
	tcpData := make([]byte, 3)
	if _, err := io.ReadFull(upstream, tcpData); err != nil || string(tcpData) != "tcp" {
		t.Fatalf("legacy TCP forwarding failed: %q %v", tcpData, err)
	}

	udpClient, err := net.DialTimeout("udp", udpSvc.Addr().String(), time.Second)
	if err != nil {
		t.Fatal(err)
	}
	defer udpClient.Close()
	udpClient.Write([]byte("udp"))
	udpTarget.SetReadDeadline(time.Now().Add(time.Second))
	udpData := make([]byte, 3)
	if n, _, err := udpTarget.ReadFrom(udpData); err != nil || n != 3 || string(udpData) != "udp" {
		t.Fatalf("legacy UDP forwarding failed: %q %v", udpData, err)
	}
}

func TestOriginTLSCertificateValidation(t *testing.T) {
	logger.SetDefault(xlogger.NewLogger(xlogger.OutputOption(io.Discard)))
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.Write([]byte("secure-origin")) }))
	defer server.Close()
	cert := server.Certificate()
	root := string(pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: cert.Raw}))
	address := strings.TrimPrefix(server.URL, "https://")
	for _, tc := range []struct {
		name, ca, serverName string
		success              bool
	}{
		{"trusted", root, "example.com", true},
		{"wrong-name", root, "wrong.invalid", false},
		{"untrusted", "", "example.com", false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			cfg := &config.ServiceConfig{Name: "origin-test", Addr: "127.0.0.1:0",
				Listener: &config.ListenerConfig{Type: "tcp"},
				Handler: &config.HandlerConfig{Type: "tcp", Metadata: map[string]any{
					"originTLS": true, "originServerName": tc.serverName, "originRootCA": tc.ca,
				}},
				Forwarder: &config.ForwarderConfig{Nodes: []*config.ForwardNodeConfig{{Name: "origin", Addr: address}}},
			}
			svc, err := serviceparser.ParseService(cfg)
			if err != nil {
				t.Fatal(err)
			}
			defer svc.Close()
			go svc.Serve()
			client, err := net.DialTimeout("tcp", svc.Addr().String(), time.Second)
			if err != nil {
				t.Fatal(err)
			}
			defer client.Close()
			client.SetDeadline(time.Now().Add(3 * time.Second))
			fmt.Fprint(client, "GET / HTTP/1.0\r\nHost: example.com\r\n\r\n")
			data, _ := io.ReadAll(client)
			if strings.Contains(string(data), "secure-origin") != tc.success {
				t.Fatalf("unexpected TLS result: %q", data)
			}
		})
	}
}

func TestOriginTLSProxyHeaderIsEncrypted(t *testing.T) {
	server := httptest.NewTLSServer(http.NotFoundHandler())
	certificate := server.TLS.Certificates[0]
	root := string(pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: server.Certificate().Raw}))
	server.Close()
	upstream, err := tls.Listen("tcp", "127.0.0.1:0", &tls.Config{Certificates: []tls.Certificate{certificate}, MinVersion: tls.VersionTLS12})
	if err != nil {
		t.Fatal(err)
	}
	defer upstream.Close()
	received := make(chan string, 1)
	go func() {
		c, err := upstream.Accept()
		if err != nil {
			received <- ""
			return
		}
		defer c.Close()
		c.SetDeadline(time.Now().Add(3 * time.Second))
		reader := bufio.NewReader(c)
		header, _ := reader.ReadString('\n')
		payload := make([]byte, 5)
		io.ReadFull(reader, payload)
		received <- header + string(payload)
	}()
	cfg := &config.ServiceConfig{Name: "tls-proxy-test", Addr: "127.0.0.1:0",
		Listener: &config.ListenerConfig{Type: "tcp"},
		Handler: &config.HandlerConfig{Type: "tcp", Metadata: map[string]any{
			"originTLS": true, "originServerName": "example.com", "originRootCA": root, "proxyProtocol": 1,
		}},
		Forwarder: &config.ForwarderConfig{Nodes: []*config.ForwardNodeConfig{{Name: "origin", Addr: upstream.Addr().String()}}},
	}
	svc, err := serviceparser.ParseService(cfg)
	if err != nil {
		t.Fatal(err)
	}
	defer svc.Close()
	go svc.Serve()
	c, err := net.DialTimeout("tcp", svc.Addr().String(), time.Second)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	c.SetDeadline(time.Now().Add(3 * time.Second))
	c.Write([]byte("hello"))
	select {
	case data := <-received:
		if !strings.HasPrefix(data, "PROXY TCP4 ") || !strings.HasSuffix(data, "\r\nhello") {
			t.Fatalf("unexpected decrypted data: %q", data)
		}
	case <-time.After(4 * time.Second):
		t.Fatal("TLS origin did not receive header")
	}
}

func TestSharedSNIEndToEndTLSForwarding(t *testing.T) {
	logger.SetDefault(xlogger.NewLogger(xlogger.OutputOption(io.Discard)))
	first := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.Write([]byte("origin-one")) }))
	defer first.Close()
	second := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.Write([]byte("origin-two")) }))
	defer second.Close()
	held, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	address := held.Addr().String()
	held.Close()
	for i, target := range []string{first.URL, second.URL} {
		name := fmt.Sprintf("site%d.example.com", i)
		cfg := &config.ServiceConfig{Name: fmt.Sprintf("shared-%d", i), Addr: address,
			Listener:  &config.ListenerConfig{Type: "tcp", Metadata: map[string]any{"sniNames": []string{name}}},
			Handler:   &config.HandlerConfig{Type: "tcp"},
			Forwarder: &config.ForwarderConfig{Nodes: []*config.ForwardNodeConfig{{Name: "origin", Addr: strings.TrimPrefix(target, "https://")}}},
		}
		svc, err := serviceparser.ParseService(cfg)
		if err != nil {
			t.Fatal(err)
		}
		defer svc.Close()
		go svc.Serve()
	}
	for i, want := range []string{"origin-one", "origin-two"} {
		name := fmt.Sprintf("site%d.example.com", i)
		c, err := tls.DialWithDialer(&net.Dialer{Timeout: time.Second}, "tcp", address, &tls.Config{ServerName: name, InsecureSkipVerify: true})
		if err != nil {
			t.Fatal(err)
		}
		c.SetDeadline(time.Now().Add(3 * time.Second))
		fmt.Fprintf(c, "GET / HTTP/1.0\r\nHost: %s\r\n\r\n", name)
		data, err := io.ReadAll(c)
		c.Close()
		if err != nil || !strings.Contains(string(data), want) {
			t.Fatalf("SNI forwarding failed: %q %v", data, err)
		}
	}
}
