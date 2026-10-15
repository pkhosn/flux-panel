package util

import (
	"encoding/json"
	"github.com/go-gost/x/metadata"
	"testing"
)

func TestJSONNumbers(t *testing.T) {
	var values map[string]any
	if err := json.Unmarshal([]byte(`{"sniMaxHandshakes":128,"sniProxy":1}`), &values); err != nil {
		t.Fatal(err)
	}
	md := metadata.NewMetadata(values)
	if GetInt(md, "sniMaxHandshakes") != 128 || GetInt(md, "sniProxy") != 1 || !GetBool(md, "sniProxy") {
		t.Fatal("JSON numeric metadata lost")
	}
}
