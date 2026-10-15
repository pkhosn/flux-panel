package socket

import (
	"encoding/json"
	"fmt"
	"regexp"

	"github.com/go-gost/x/config"
	"github.com/go-gost/x/registry"
)

// Only panel forwarding names are owned here; relay/API services remain untouched.
var forwardName = regexp.MustCompile(`^[0-9]+_[0-9]+_[0-9]+(_tcp|_udp)?$`)

func reconcileForwards(desired []config.ServiceConfig) error {
	seen := make(map[string]bool)
	for _, entry := range desired {
		if !forwardName.MatchString(entry.Name) || seen[entry.Name] {
			return fmt.Errorf("invalid or duplicate forwarding service %s", entry.Name)
		}
		seen[entry.Name] = true
	}
	var remove []string
	for _, entry := range config.Global().Services {
		if forwardName.MatchString(entry.Name) {
			remove = append(remove, entry.Name)
		}
	}
	// Each retry starts from a known empty forwarding set, including partially restored services.
	for _, name := range remove {
		registry.ServiceRegistry().Unregister(name)
	}
	config.OnUpdate(func(c *config.Config) error {
		var keep []*config.ServiceConfig
		for _, entry := range c.Services {
			if !forwardName.MatchString(entry.Name) {
				keep = append(keep, entry)
			}
		}
		c.Services = keep
		return nil
	})
	for _, entry := range desired {
		if err := createServices(createServicesRequest{Data: []config.ServiceConfig{entry}}); err != nil {
			return err
		}
	}
	return nil
}

func (w *WebSocketReporter) handleReconcileForwards(data interface{}) error {
	raw, err := json.Marshal(data)
	if err != nil {
		return err
	}
	raw, err = w.preprocessDurationFields(raw)
	if err != nil {
		return err
	}
	var desired []config.ServiceConfig
	if err := json.Unmarshal(raw, &desired); err != nil {
		return err
	}
	return reconcileForwards(desired)
}
