package local

import (
	"crypto"
	"crypto/tls"
	"crypto/x509"
	"fmt"
	"strings"
	"time"

	"github.com/go-gost/core/bypass"
	mdata "github.com/go-gost/core/metadata"
	mdutil "github.com/go-gost/x/metadata/util"
	"github.com/go-gost/x/registry"
)

type metadata struct {
	readTimeout   time.Duration
	httpKeepalive bool
	proxyProtocol int
	leastTargets  []string
	originTLS     *tls.Config

	sniffing                    bool
	sniffingTimeout             time.Duration
	sniffingWebsocket           bool
	sniffingWebsocketSampleRate float64

	certificate *x509.Certificate
	privateKey  crypto.PrivateKey
	alpn        string
	mitmBypass  bypass.Bypass
}

func (h *forwardHandler) parseMetadata(md mdata.Metadata) (err error) {
	h.md.leastTargets = mdutil.GetStrings(md, "leastTargets")
	h.md.readTimeout = mdutil.GetDuration(md, "readTimeout")
	if h.md.readTimeout <= 0 {
		h.md.readTimeout = 15 * time.Second
	}

	h.md.httpKeepalive = mdutil.GetBool(md, "http.keepalive")
	h.md.proxyProtocol = mdutil.GetInt(md, "proxyProtocol")
	if h.md.proxyProtocol != 0 && h.md.proxyProtocol != 1 && h.md.proxyProtocol != 2 {
		return fmt.Errorf("unsupported proxyProtocol version: %d", h.md.proxyProtocol)
	}

	if mdutil.GetBool(md, "originTLS") {
		cfg := &tls.Config{MinVersion: tls.VersionTLS12, ServerName: mdutil.GetString(md, "originServerName")}
		if ca := mdutil.GetString(md, "originRootCA"); ca != "" {
			pool, poolErr := x509.SystemCertPool()
			if poolErr != nil {
				pool = x509.NewCertPool()
			}
			if !pool.AppendCertsFromPEM([]byte(ca)) {
				return fmt.Errorf("invalid origin TLS root certificate")
			}
			cfg.RootCAs = pool
		}
		if protocols := mdutil.GetString(md, "originALPN"); protocols != "" {
			cfg.NextProtos = strings.Split(protocols, ",")
		}
		h.md.originTLS = cfg
	}

	h.md.sniffing = mdutil.GetBool(md, "sniffing")
	h.md.sniffingTimeout = mdutil.GetDuration(md, "sniffing.timeout")
	h.md.sniffingWebsocket = mdutil.GetBool(md, "sniffing.websocket")
	h.md.sniffingWebsocketSampleRate = mdutil.GetFloat(md, "sniffing.websocket.sampleRate")

	certFile := mdutil.GetString(md, "mitm.certFile", "mitm.caCertFile")
	keyFile := mdutil.GetString(md, "mitm.keyFile", "mitm.caKeyFile")
	if certFile != "" && keyFile != "" {
		tlsCert, err := tls.LoadX509KeyPair(certFile, keyFile)
		if err != nil {
			return err
		}
		h.md.certificate, err = x509.ParseCertificate(tlsCert.Certificate[0])
		if err != nil {
			return err
		}
		h.md.privateKey = tlsCert.PrivateKey
	}
	h.md.alpn = mdutil.GetString(md, "mitm.alpn")
	h.md.mitmBypass = registry.BypassRegistry().Get(mdutil.GetString(md, "mitm.bypass"))

	return
}
