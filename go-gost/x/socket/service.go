package socket

import (
	"errors"
	"fmt"
	"net"
	"strings"
	"time"

	"github.com/go-gost/core/service"
	"github.com/go-gost/x/config"
	parser "github.com/go-gost/x/config/parsing/service"
	kill "github.com/go-gost/x/internal/util/port"
	"github.com/go-gost/x/registry"
)

func createServices(req createServicesRequest) error {

	if len(req.Data) == 0 {
		return errors.New("services list cannot be empty")
	}

	// 第一阶段：验证所有服务配置
	var parsedServices []struct {
		config  config.ServiceConfig
		service service.Service
	}
	cleanupParsed := func() {
		for _, parsed := range parsedServices {
			parsed.service.Close()
		}
	}

	for _, serviceConfig := range req.Data {
		name := strings.TrimSpace(serviceConfig.Name)
		if name == "" {
			cleanupParsed()
			return errors.New("service name is required")
		}
		serviceConfig.Name = name

		if registry.ServiceRegistry().IsRegistered(name) {
			cleanupParsed()
			return errors.New("service " + name + " already exists")
		}

		svc, err := parser.ParseService(&serviceConfig)
		if err != nil {
			cleanupParsed()
			return errors.New("create service " + name + " failed: " + err.Error())
		}

		parsedServices = append(parsedServices, struct {
			config  config.ServiceConfig
			service service.Service
		}{serviceConfig, svc})
	}

	// 第二阶段：注册所有服务
	var registeredServices []string
	for _, ps := range parsedServices {
		if err := registry.ServiceRegistry().Register(ps.config.Name, ps.service); err != nil {
			// 如果注册失败，回滚已注册的服务
			for _, regName := range registeredServices {
				registry.ServiceRegistry().Unregister(regName)
			}
			ps.service.Close()
			for _, pending := range parsedServices[len(registeredServices)+1:] {
				pending.service.Close()
			}
			return errors.New("service " + ps.config.Name + " already exists")
		}
		registeredServices = append(registeredServices, ps.config.Name)
	}

	// 第三阶段：启动所有服务
	for _, ps := range parsedServices {
		if svc := registry.ServiceRegistry().Get(ps.config.Name); svc != nil {
			if paused, _ := ps.config.Metadata["paused"].(bool); paused {
				svc.Close()
			} else {
				go svc.Serve()
			}
		}
	}

	// 第四阶段：更新配置
	config.OnUpdate(func(c *config.Config) error {
		for _, ps := range parsedServices {
			c.Services = append(c.Services, &ps.config)
		}
		return nil
	})

	return nil
}

func updateServices(req updateServicesRequest) error {

	if len(req.Data) == 0 {
		return errors.New("services list cannot be empty")
	}

	// 保存旧配置，以便新服务解析或注册失败时恢复监听。
	oldConfigs := make([]config.ServiceConfig, 0, len(req.Data))
	seen := make(map[string]bool)
	current := config.Global()
	for i := range req.Data {
		name := strings.TrimSpace(req.Data[i].Name)
		if name == "" {
			return errors.New("service name is required")
		}
		if seen[name] {
			return errors.New("duplicate service " + name)
		}
		req.Data[i].Name = name
		seen[name] = true
		if registry.ServiceRegistry().Get(name) == nil {
			return errors.New("service " + name + " not found")
		}
		var previous *config.ServiceConfig
		for _, entry := range current.Services {
			if entry.Name == name {
				previous = entry
				break
			}
		}
		if previous == nil {
			return errors.New("service " + name + " config not found")
		}
		oldConfigs = append(oldConfigs, *previous)
	}

	for _, previous := range oldConfigs {
		registry.ServiceRegistry().Unregister(previous.Name)
	}

	restore := func() error {
		for _, entry := range req.Data {
			if svc := registry.ServiceRegistry().Get(entry.Name); svc != nil {
				registry.ServiceRegistry().Unregister(entry.Name)
			}
		}
		var failures []string
		for _, previous := range oldConfigs {
			svc, err := parser.ParseService(&previous)
			if err == nil {
				err = registry.ServiceRegistry().Register(previous.Name, svc)
				if err == nil {
					go svc.Serve()
				} else {
					svc.Close()
				}
			}
			if err != nil {
				failures = append(failures, previous.Name+": "+err.Error())
			}
		}
		if len(failures) > 0 {
			return errors.New(strings.Join(failures, "; "))
		}
		return nil
	}

	parsed := make([]service.Service, 0, len(req.Data))
	for _, entry := range req.Data {
		entry.Name = strings.TrimSpace(entry.Name)
		svc, err := parser.ParseService(&entry)
		if err != nil {
			for _, pending := range parsed {
				pending.Close()
			}
			if rollbackErr := restore(); rollbackErr != nil {
				return fmt.Errorf("update service %s failed: %w; restore failed: %v", entry.Name, err, rollbackErr)
			}
			return fmt.Errorf("update service %s failed: %w", entry.Name, err)
		}
		parsed = append(parsed, svc)
	}
	for i, entry := range req.Data {
		if err := registry.ServiceRegistry().Register(entry.Name, parsed[i]); err != nil {
			for _, pending := range parsed[i:] {
				pending.Close()
			}
			if rollbackErr := restore(); rollbackErr != nil {
				return fmt.Errorf("register service %s failed: %w; restore failed: %v", entry.Name, err, rollbackErr)
			}
			return fmt.Errorf("register service %s failed: %w", entry.Name, err)
		}
	}

	for _, svc := range parsed {
		go svc.Serve()
	}

	config.OnUpdate(func(c *config.Config) error {
		for _, serviceConfig := range req.Data {
			for i := range c.Services {
				if c.Services[i].Name == serviceConfig.Name {
					c.Services[i] = &serviceConfig
					break
				}
			}
		}
		return nil
	})

	return nil
}

func deleteServices(req deleteServicesRequest) error {

	if len(req.Services) == 0 {
		return errors.New("services list cannot be empty")
	}

	// 第一阶段：验证所有服务是否存在
	var servicesToDelete []struct {
		name    string
		service service.Service
	}

	for _, serviceName := range req.Services {
		name := strings.TrimSpace(serviceName)
		if name == "" {
			return errors.New("service name is required")
		}

		svc := registry.ServiceRegistry().Get(name)
		if svc == nil {
			return errors.New("service " + name + " not found")
		}

		servicesToDelete = append(servicesToDelete, struct {
			name    string
			service service.Service
		}{name, svc})
	}

	// 第二阶段：删除所有服务
	for _, std := range servicesToDelete {
		registry.ServiceRegistry().Unregister(std.name)
		std.service.Close()
	}

	// 第三阶段：更新配置
	config.OnUpdate(func(c *config.Config) error {
		services := c.Services
		c.Services = nil
		for _, s := range services {
			shouldDelete := false
			for _, std := range servicesToDelete {
				if s.Name == std.name {
					shouldDelete = true
					break
				}
			}
			if !shouldDelete {
				c.Services = append(c.Services, s)
			}
		}
		return nil
	})

	return nil
}

func pauseServices(req pauseServicesRequest) error {

	if len(req.Services) == 0 {
		return errors.New("services list cannot be empty")
	}

	// 第一阶段：验证所有服务是否存在，并筛选需要暂停的服务
	var servicesToPause []struct {
		name    string
		service service.Service
	}
	//var skippedServices []string

	cfg := config.Global()
	for _, serviceName := range req.Services {
		name := strings.TrimSpace(serviceName)
		if name == "" {
			return errors.New("service name is required")
		}

		svc := registry.ServiceRegistry().Get(name)
		if svc == nil {
			return errors.New(fmt.Sprintf("service %s not found", name))
		}

		//// 检查服务是否已经暂停
		//var serviceConfig *config.ServiceConfig
		//for _, s := range cfg.Services {
		//	if s.Name == name {
		//		serviceConfig = s
		//		break
		//	}
		//}
		//
		//// 如果服务已经暂停，跳过
		//if serviceConfig != nil && serviceConfig.Metadata != nil {
		//	if pausedVal, exists := serviceConfig.Metadata["paused"]; exists && pausedVal == true {
		//		skippedServices = append(skippedServices, name)
		//		continue
		//	}
		//}

		servicesToPause = append(servicesToPause, struct {
			name    string
			service service.Service
		}{name, svc})
	}

	// 第二阶段：事务性暂停所有服务
	var pausedServices []struct {
		name          string
		service       service.Service
		serviceConfig *config.ServiceConfig
	}

	// 获取服务配置
	serviceConfigs := make(map[string]*config.ServiceConfig)
	for _, s := range cfg.Services {
		serviceConfigs[s.Name] = s
	}

	// 逐个暂停服务，如果失败则回滚
	for _, stp := range servicesToPause {
		serviceConfig := serviceConfigs[stp.name]
		if serviceConfig == nil {
			// 找不到配置，回滚已暂停的服务
			if rollbackErr := rollbackPausedServices(pausedServices); rollbackErr != nil {
				return fmt.Errorf("service %s configuration not found; rollback failed: %w", stp.name, rollbackErr)
			}
			return errors.New(fmt.Sprintf("service %s configuration not found", stp.name))
		}

		// 暂停服务
		if err := stp.service.Close(); err != nil && !errors.Is(err, net.ErrClosed) {
			if rollbackErr := rollbackPausedServices(pausedServices); rollbackErr != nil {
				return fmt.Errorf("pause service %s failed: %v; rollback failed: %w", stp.name, err, rollbackErr)
			}
			return fmt.Errorf("pause service %s failed: %w", stp.name, err)
		}

		// 强制断开端口的所有连接
		if serviceConfig.Addr != "" {
			_ = kill.ForceClosePortConnections(serviceConfig.Addr)
		}

		// 记录已暂停的服务
		pausedServices = append(pausedServices, struct {
			name          string
			service       service.Service
			serviceConfig *config.ServiceConfig
		}{stp.name, stp.service, serviceConfig})
	}

	// 第三阶段：更新配置，标记暂停状态
	err := config.OnUpdate(func(c *config.Config) error {
		for _, stp := range servicesToPause {
			for i := range c.Services {
				if c.Services[i].Name == stp.name {
					if c.Services[i].Metadata == nil {
						c.Services[i].Metadata = make(map[string]any)
					}
					c.Services[i].Metadata["paused"] = true
					break
				}
			}
		}
		return nil
	})

	if err != nil {
		// 配置更新失败，需要回滚所有暂停的服务
		if rollbackErr := rollbackPausedServices(pausedServices); rollbackErr != nil {
			return fmt.Errorf("failed to update config: %v; rollback failed: %w", err, rollbackErr)
		}
		return fmt.Errorf("failed to update config: %w", err)
	}

	return nil
}

func resumeServices(req resumeServicesRequest) error {
	if len(req.Services) == 0 {
		return errors.New("services list cannot be empty")
	}

	// 第一阶段：验证所有服务是否存在，并筛选需要恢复的服务
	var servicesToResume []struct {
		name          string
		service       service.Service
		serviceConfig *config.ServiceConfig
	}
	var skippedServices []string

	cfg := config.Global()
	for _, serviceName := range req.Services {
		name := strings.TrimSpace(serviceName)
		if name == "" {
			return errors.New("service name is required")
		}

		// 检查服务是否存在
		svc := registry.ServiceRegistry().Get(name)
		if svc == nil {
			return errors.New(fmt.Sprintf("service %s not found", name))
		}

		// 查找配置中的服务
		var serviceConfig *config.ServiceConfig
		for _, s := range cfg.Services {
			if s.Name == name {
				serviceConfig = s
				break
			}
		}

		if serviceConfig == nil {
			return errors.New(fmt.Sprintf("service %s configuration not found", name))
		}

		// 检查是否处于暂停状态
		paused := false
		if serviceConfig.Metadata != nil {
			if pausedVal, exists := serviceConfig.Metadata["paused"]; exists && pausedVal == true {
				paused = true
			}
		}

		// 如果服务没有暂停(即正在运行)，跳过
		if !paused {
			skippedServices = append(skippedServices, name)
			continue
		}

		servicesToResume = append(servicesToResume, struct {
			name          string
			service       service.Service
			serviceConfig *config.ServiceConfig
		}{name, svc, serviceConfig})
	}

	// 第二阶段：事务性恢复所有服务
	var resumedServices []struct {
		name          string
		service       service.Service
		serviceConfig *config.ServiceConfig
	}

	// 逐个恢复服务，如果失败则回滚
	for _, str := range servicesToResume {
		// 先关闭现有服务
		if err := str.service.Close(); err != nil && !errors.Is(err, net.ErrClosed) {
			return fmt.Errorf("close paused service %s failed: %w", str.name, err)
		}
		registry.ServiceRegistry().Unregister(str.name)

		// 等待端口释放
		time.Sleep(100 * time.Millisecond)

		// 重新解析并启动服务
		svc, err := parser.ParseService(str.serviceConfig)
		if err != nil {
			// 恢复失败，回滚已恢复的服务
			registry.ServiceRegistry().Register(str.name, str.service)
			rollbackResumedServices(resumedServices)
			return errors.New(fmt.Sprintf("resume service %s failed: %s", str.name, err.Error()))
		}

		if err := registry.ServiceRegistry().Register(str.name, svc); err != nil {
			svc.Close()
			registry.ServiceRegistry().Register(str.name, str.service)
			// 恢复失败，回滚已恢复的服务
			rollbackResumedServices(resumedServices)
			return errors.New(fmt.Sprintf("service %s already exists", str.name))
		}

		go svc.Serve()

		// 记录已成功恢复的服务
		resumedServices = append(resumedServices, str)
	}

	// 第三阶段：更新配置，移除暂停状态
	err := config.OnUpdate(func(c *config.Config) error {
		for _, str := range servicesToResume {
			for i := range c.Services {
				if c.Services[i].Name == str.name {
					if c.Services[i].Metadata != nil {
						delete(c.Services[i].Metadata, "paused")
						// 如果 metadata 为空，设置为 nil
						if len(c.Services[i].Metadata) == 0 {
							c.Services[i].Metadata = nil
						}
					}
					break
				}
			}
		}
		return nil
	})

	if err != nil {
		// 配置更新失败，回滚所有已恢复的服务
		rollbackResumedServices(resumedServices)
		return errors.New(fmt.Sprintf("Failed to update config, rolling back resumed services: %v", err))
	}

	return nil
}

func rollbackPausedServices(pausedServices []struct {
	name          string
	service       service.Service
	serviceConfig *config.ServiceConfig
}) error {
	var failures []string
	for _, pss := range pausedServices {
		registry.ServiceRegistry().Unregister(pss.name)
		// 重新解析并启动服务
		svc, err := parser.ParseService(pss.serviceConfig)
		if err != nil {
			failures = append(failures, pss.name+": "+err.Error())
			continue
		}

		if err := registry.ServiceRegistry().Register(pss.name, svc); err != nil {
			svc.Close()
			failures = append(failures, pss.name+": "+err.Error())
			continue
		}

		go svc.Serve()

		// 移除暂停状态标记
		config.OnUpdate(func(c *config.Config) error {
			for i := range c.Services {
				if c.Services[i].Name == pss.name {
					if c.Services[i].Metadata != nil {
						delete(c.Services[i].Metadata, "paused")
						if len(c.Services[i].Metadata) == 0 {
							c.Services[i].Metadata = nil
						}
					}
					break
				}
			}
			return nil
		})
	}
	if len(failures) > 0 {
		return errors.New(strings.Join(failures, "; "))
	}
	return nil
}

func rollbackResumedServices(resumedServices []struct {
	name          string
	service       service.Service
	serviceConfig *config.ServiceConfig
}) {
	for _, rss := range resumedServices {
		// 关闭已恢复的服务
		registry.ServiceRegistry().Unregister(rss.name)
		registry.ServiceRegistry().Register(rss.name, rss.service)

		// 重新标记为暂停状态
		config.OnUpdate(func(c *config.Config) error {
			for i := range c.Services {
				if c.Services[i].Name == rss.name {
					if c.Services[i].Metadata == nil {
						c.Services[i].Metadata = make(map[string]any)
					}
					c.Services[i].Metadata["paused"] = true
					break
				}
			}
			return nil
		})
	}
}

type resumeServicesRequest struct {
	Services []string `json:"services"`
}

type pauseServicesRequest struct {
	Services []string `json:"services"`
}

type deleteServicesRequest struct {
	Services []string `json:"services"`
}

type updateServicesRequest struct {
	Data []config.ServiceConfig `json:"data"`
}

type createServicesRequest struct {
	Data []config.ServiceConfig `json:"data"`
}
