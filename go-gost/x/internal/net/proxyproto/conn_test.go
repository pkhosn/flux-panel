package proxyproto

import (
	"bufio"
	"bytes"
	"net"
	"testing"

	proxyproto "github.com/pires/go-proxyproto"
)

func TestWriteClientHeader(t *testing.T) {
	src := &net.TCPAddr{IP: net.ParseIP("192.0.2.10"), Port: 12345}
	dst := &net.TCPAddr{IP: net.ParseIP("198.51.100.20"), Port: 443}
	for _, version := range []int{1, 2} {
		var output bytes.Buffer
		if err := WriteClientHeader(version, src, dst, &output); err != nil {
			t.Fatalf("v%d write failed: %v", version, err)
		}
		header, err := proxyproto.Read(bufio.NewReader(&output))
		if err != nil {
			t.Fatalf("v%d parse failed: %v", version, err)
		}
		if int(header.Version) != version || header.SourceAddr.String() != src.String() || header.DestinationAddr.String() != dst.String() {
			t.Fatalf("v%d incorrect header: %+v", version, header)
		}
	}
	if err := WriteClientHeader(3, src, dst, &bytes.Buffer{}); err == nil {
		t.Fatal("unsupported version accepted")
	}
}
