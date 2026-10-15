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
	sniPolicy         sniPolicy
	sniDefault        bool
	mptcp             bool
	proxyTrustedCIDRs []*net.IPNet
}

func (l *tcpListener) parseMetadata(md md.Metadata) (err error) {
	l.md.sniNames = mdutil.GetStrings(md, "sniNames")
	l.md.sniPolicy = defaultSNIPolicy()
	l.md.sniPolicy.Proxy = mdutil.GetInt(md, "sniProxy")
	l.md.sniPolicy.Trusted = mdutil.GetStrings(md, "sniTrustedCIDRs")
	l.md.sniPolicy.Allowed = mdutil.GetStrings(md, "sniAllowed")
	if md != nil && md.IsExists("sniRejectEmpty") {
		l.md.sniPolicy.RejectEmpty = mdutil.GetBool(md, "sniRejectEmpty")
	}
	l.md.sniPolicy.UnknownDefault = mdutil.GetBool(md, "sniUnknownDefault")
	if md != nil && md.IsExists("sniMaxHandshakes") {
		l.md.sniPolicy.MaxHandshakes = mdutil.GetInt(md, "sniMaxHandshakes")
	}
	if md != nil && md.IsExists("sniTimeout") {
		l.md.sniPolicy.Timeout = mdutil.GetDuration(md, "sniTimeout")
	}
	l.md.sniDefault = mdutil.GetBool(md, "sniDefault")
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
