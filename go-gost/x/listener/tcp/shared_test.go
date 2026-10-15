package tcp

import (
	"crypto/tls"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestSharedSNIRoutesAndClosesIndependently(t *testing.T) {
	certServer := httptest.NewTLSServer(http.NotFoundHandler())
	certificate := certServer.TLS.Certificates[0]
	certServer.Close()
	ln, err := listenSharedSNI("tcp", "127.0.0.1:0", []string{"one.example.com"})
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	second, err := listenSharedSNI("tcp", "127.0.0.1:0", []string{"two.example.com"})
	if err != nil {
		t.Fatal(err)
	}
	defer second.Close()
	if _, err := listenSharedSNI("tcp", "127.0.0.1:0", []string{"one.example.com"}); err == nil {
		t.Fatal("duplicate SNI accepted")
	}
	serve := func(listener net.Listener, response string) {
		go func() {
			for {
				c, err := listener.Accept()
				if err != nil {
					return
				}
				go func() {
					defer c.Close()
					c.SetDeadline(time.Now().Add(2 * time.Second))
					tc := tls.Server(c, &tls.Config{Certificates: []tls.Certificate{certificate}})
					if tc.Handshake() == nil {
						tc.Write([]byte(response))
					}
				}()
			}
		}()
	}
	serve(ln, "one")
	serve(second, "two")
	check := func(name, want string) {
		t.Helper()
		d := net.Dialer{Timeout: time.Second}
		// Test client accepts the fixture cert; the shared listener never terminates TLS.
		c, err := tls.DialWithDialer(&d, "tcp", ln.Addr().String(), &tls.Config{ServerName: name, InsecureSkipVerify: true})
		if want == "" {
			if err == nil {
				c.Close()
				t.Fatal("unknown SNI accepted")
			}
			return
		}
		if err != nil {
			t.Fatal(err)
		}
		defer c.Close()
		c.SetDeadline(time.Now().Add(2 * time.Second))
		data, err := io.ReadAll(c)
		if err != nil || string(data) != want {
			t.Fatalf("%s: %q %v", name, data, err)
		}
	}
	check("one.example.com", "one")
	check("two.example.com", "two")
	check("unknown.example.com", "")
	check("", "")
	ln.Close()
	check("one.example.com", "")
	check("two.example.com", "two")
	resumed, err := listenSharedSNI("tcp", "127.0.0.1:0", []string{"one.example.com"})
	if err != nil {
		t.Fatal(err)
	}
	serve(resumed, "resumed")
	check("one.example.com", "resumed")
	resumed.Close()
	second.Close()
	rebound, err := net.Listen("tcp", ln.Addr().String())
	if err != nil {
		t.Fatalf("port leaked: %v", err)
	}
	rebound.Close()
}

func TestSharedSNIRejectsInvalidNames(t *testing.T) {
	for _, name := range []string{"", "127.0.0.1", "*.example.com", "bad..example.com"} {
		if _, err := listenSharedSNI("tcp", "127.0.0.1:0", []string{name}); err == nil {
			t.Fatalf("invalid SNI: %q", name)
		}
	}
}

func TestSharedSNICloseDuringClassification(t *testing.T) {
	for i := 0; i < 10; i++ {
		ln, err := listenSharedSNI("tcp", "127.0.0.1:0", []string{"pending.example.com"})
		if err != nil {
			t.Fatal(err)
		}
		client, err := net.DialTimeout("tcp", ln.Addr().String(), time.Second)
		if err != nil {
			t.Fatal(err)
		}
		// A partial record must not prevent closing the listener and reusing the socket.
		client.Write([]byte{22, 3})
		address := ln.Addr().String()
		ln.Close()
		client.Close()
		rebound, err := net.Listen("tcp", address)
		if err != nil {
			t.Fatal(err)
		}
		rebound.Close()
	}
}

func TestSharedPolicyProxyAndFallback(t *testing.T) {
	certServer := httptest.NewTLSServer(http.NotFoundHandler())
	certificate := certServer.TLS.Certificates[0]
	certServer.Close()
	for _, tc := range []struct {
		name       string
		proxy      bool
		trusted    string
		serverName string
		allowed    []string
		accept     bool
	}{
		{"known-proxy", true, "127.0.0.0/8", "known.example.com", nil, true},
		{"unknown-fallback", false, "", "unknown.example.com", nil, true},
		{"empty-fallback", false, "", "", nil, true},
		{"range-blocks-fallback", false, "", "unknown.invalid", []string{"example.com"}, false},
		{"untrusted-proxy", true, "192.0.2.0/24", "known.example.com", nil, false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			policy := defaultSNIPolicy()
			policy.RejectEmpty = false
			policy.UnknownDefault = true
			policy.Allowed = tc.allowed
			if tc.proxy {
				policy.Proxy = 1
				policy.Trusted = []string{tc.trusted}
			}
			ln, err := listenSharedPolicy("tcp", "127.0.0.1:0", []string{"known.example.com"}, policy, true)
			if err != nil {
				t.Fatal(err)
			}
			defer ln.Close()
			go func() {
				c, err := ln.Accept()
				if err != nil {
					return
				}
				defer c.Close()
				c.SetDeadline(time.Now().Add(2 * time.Second))
				secure := tls.Server(c, &tls.Config{Certificates: []tls.Certificate{certificate}})
				if secure.Handshake() == nil {
					secure.Write([]byte("fallback"))
				}
			}()
			c, err := net.DialTimeout("tcp", ln.Addr().String(), time.Second)
			if err != nil {
				t.Fatal(err)
			}
			defer c.Close()
			c.SetDeadline(time.Now().Add(2 * time.Second))
			if tc.proxy {
				io.WriteString(c, "PROXY TCP4 203.0.113.9 127.0.0.1 1234 443\r\n")
			}
			secure := tls.Client(c, &tls.Config{ServerName: tc.serverName, InsecureSkipVerify: true})
			err = secure.Handshake()
			if !tc.accept {
				if err == nil {
					t.Fatal("forbidden TLS accepted")
				}
				return
			}
			if err != nil {
				t.Fatal(err)
			}
			data, err := io.ReadAll(secure)
			if err != nil || string(data) != "fallback" {
				t.Fatalf("bad response: %q %v", data, err)
			}
		})
	}
}
