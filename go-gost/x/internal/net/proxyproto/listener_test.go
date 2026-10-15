package proxyproto

import (
	"net"
	"strings"
	"testing"
	"time"
)

func TestTrustedListenerRequiresTrustedProxyHeader(t *testing.T) {
	raw, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer raw.Close()
	_, trusted, _ := net.ParseCIDR("127.0.0.0/8")
	wrapped := WrapListenerTrusted(1, raw, time.Second, []*net.IPNet{trusted})

	client, err := net.Dial("tcp", raw.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	client.Write([]byte("plain"))
	accepted, err := wrapped.Accept()
	if err != nil {
		t.Fatal(err)
	}
	accepted.SetReadDeadline(time.Now().Add(time.Second))
	data := make([]byte, 1)
	if _, err := accepted.Read(data); err == nil {
		t.Fatal("trusted proxy without header was accepted")
	}
	accepted.Close()

	client2, err := net.Dial("tcp", raw.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	defer client2.Close()
	src := &net.TCPAddr{IP: net.ParseIP("192.0.2.33"), Port: 1234}
	dst := &net.TCPAddr{IP: net.ParseIP("127.0.0.1"), Port: 443}
	if err := WriteClientHeader(1, src, dst, client2); err != nil {
		t.Fatal(err)
	}
	client2.Write([]byte("payload"))
	accepted2, err := wrapped.Accept()
	if err != nil {
		t.Fatal(err)
	}
	defer accepted2.Close()
	accepted2.SetReadDeadline(time.Now().Add(time.Second))
	if _, err := accepted2.Read(data); err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(accepted2.RemoteAddr().String(), src.IP.String()) {
		t.Fatalf("proxy source not applied: %s", accepted2.RemoteAddr())
	}
}

func TestTrustedListenerRejectsUntrustedPeer(t *testing.T) {
	raw, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer raw.Close()
	_, trusted, _ := net.ParseCIDR("192.0.2.0/24")
	wrapped := WrapListenerTrusted(1, raw, time.Second, []*net.IPNet{trusted})
	client, err := net.Dial("tcp", raw.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	if _, err := wrapped.Accept(); err == nil {
		t.Fatal("untrusted proxy peer was accepted")
	}
}
