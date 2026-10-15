package proxyproto

import (
	"fmt"
	"io"
	"net"

	proxyproto "github.com/pires/go-proxyproto"
)

func WrapClientConn(ppv int, src, dst net.Addr, c net.Conn) net.Conn {
	if ppv <= 0 {
		return c
	}

	header := proxyproto.HeaderProxyFromAddrs(byte(ppv), src, dst)
	header.WriteTo(c)
	return c
}

func WriteClientHeader(ppv int, src, dst net.Addr, c io.Writer) error {
	if ppv != 1 && ppv != 2 {
		return fmt.Errorf("unsupported proxy protocol version: %d", ppv)
	}
	header := proxyproto.HeaderProxyFromAddrs(byte(ppv), src, dst)
	_, err := header.WriteTo(c)
	return err
}
