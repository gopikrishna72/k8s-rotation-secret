package pushtofile

import (
	"bytes"
	"context"
	"encoding/base64"
	"fmt"

	"github.com/cyberark/conjur-authn-k8s-client/pkg/log"
	"github.com/cyberark/secrets-provider-for-k8s/pkg/log/messages"
	"github.com/cyberark/secrets-provider-for-k8s/pkg/secrets/clients/conjur"
)

// Secret describes how Conjur secrets are represented in the Push-to-File context.
type Secret struct {
	Alias string
	Value string
}

// FetchSecretsForGroups fetches the secrets for all the groups and returns
// map of [group name] to [a slice of secrets for the group]. Callers of this
// function should decorate any errors with messages.CSPFK052E
func FetchSecretsForGroups(
	depRetrieveSecrets conjur.RetrieveSecretsFunc,
	secretGroups []*SecretGroup,
	traceContext context.Context,
) (map[string][]*Secret, error) {
	var err error
	secretsByGroup := map[string][]*Secret{}

	secretPaths := getAllPaths(secretGroups)
	secretValueById, err := depRetrieveSecrets(secretPaths, traceContext)
	if err != nil {
		return nil, err
	}

	for _, group := range secretGroups {
		for _, spec := range group.SecretSpecs {
			sValue, ok := secretValueById[spec.Path]
			if !ok {
				err = fmt.Errorf(
					"secret with alias %q not present in fetched secrets",
					spec.Alias,
				)
				return nil, err
			}
			if spec.ContentType == "base64" {
				decodedSecretValue := make([]byte, base64.StdEncoding.DecodedLen(len(sValue)))
				_, err := base64.StdEncoding.Decode(decodedSecretValue, sValue)
				decodedSecretValue = bytes.Trim(decodedSecretValue, "\x00")
				if err != nil {
					// Log the error as a warning but still provide the original secret value
					log.Warn(messages.CSPFK064E, spec.Alias, spec.ContentType, err.Error())
				} else {
					sValue = decodedSecretValue
				}
				decodedSecretValue = []byte{}
			}
			secretsByGroup[group.Name] = append(
				secretsByGroup[group.Name],
				&Secret{
					Alias: spec.Alias,
					Value: string(sValue),
				},
			)
			sValue = []byte{}
		}
	}

	return secretsByGroup, err
}

// secretPathSet is a mathematical set of secret paths. The values of the
// underlying map use an empty struct, since no data is required.
type secretPathSet map[string]struct{}

func (s secretPathSet) Add(path string) {
	s[path] = struct{}{}
}

func getAllPaths(secretGroups []*SecretGroup) []string {
	// Create a mathematical set of all secret paths
	pathSet := secretPathSet{}
	for _, group := range secretGroups {
		for _, spec := range group.SecretSpecs {
			pathSet.Add(spec.Path)
		}
	}
	// Convert the set of secret paths to a slice of secret paths
	var paths []string
	for path := range pathSet {
		paths = append(paths, path)
	}
	return paths
}
