package service

import "testing"

func TestReportURLPreservesPanelScheme(t *testing.T) {
	for input, base := range map[string]string{"panel:6365": "http://panel:6365", "http://panel/": "http://panel", "https://panel/": "https://panel"} {
		SetHTTPReportURL(input, "0123456789abcdef0123456789abcdef")
		if httpReportURL != base+"/flow/upload?secret=0123456789abcdef0123456789abcdef" {
			t.Fatal(httpReportURL)
		}
	}
}
