package tcp

import (
	"fmt"
	"net"
	"strings"

	md "github.com/go-gost/core/metadata"
	mdutil "github.com/go-gost/x/metadata/util"
)

type metadata struct {
	sniNames          []string
	mptcp             bool
	proxyTrustedCIDRs []*net.IPNet
}

func (l *tcpListener) parseMetadata(md md.Metadata) (err error) {
	l.md.sniNames = mdutil.GetStrings(md, "sniNames")
	l.md.mptcp = mdutil.GetBool(md, "mptcp")
	for _, value := range mdutil.GetStrings(md, "proxyTrustedCIDRs") {
		_, network, err := net.ParseCIDR(strings.TrimSpace(value))
		if err != nil {
			return fmt.Errorf("invalid proxy trusted CIDR %q: %w", value, err)
		}
		l.md.proxyTrustedCIDRs = append(l.md.proxyTrustedCIDRs, network)
	}

	return
}
