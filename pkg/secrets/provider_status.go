package secrets

import (
	"fmt"
	"io"
	"os"
	"path/filepath"
)

const (
	statusFileMode = 0666
	scriptFileMode = 0755
)

// StatusUpdater defines an interface for recording a secret provider's
// status, and for copying utility scripts for checking that recorded status.
//
// SetSecretsProvided: A function that records that the secrets provider
//
//	has finished providing secrets (at least for its
//	initial iteration).
//
// SetSecretsUpdated:  A function that records that the secrets provider
//
//	has just updated the secret files or Kubernetes Secrets
//	with recently updated secret values retrieved from
//	Conjur.
//
// CopyScripts:        Copy utility scripts for checking provider status from
//
//	a "baked-in" container directory into a volume that is
//	potentially shared with application container(s).
type StatusUpdater interface {
	SetSecretsProvided() error
	SetSecretsUpdated() error
	CopyScripts() error
}

type chmodFunc func(string, os.FileMode) error
type createFunc func(string) (*os.File, error)
type openFunc func(string) (*os.File, error)
type mkdirAllFunc func(string, os.FileMode) error

type osFuncs struct {
	chmod    chmodFunc
	create   createFunc
	open     openFunc
	mkdirAll mkdirAllFunc
}

var stdOSFuncs = osFuncs{
	chmod:    os.Chmod,
	create:   os.Create,
	open:     os.Open,
	mkdirAll: os.MkdirAll,
}

// StatusUpdaterFactory defines a function type for creating a StatusUpdater
// implementation.
type StatusUpdaterFactory func() StatusUpdater

// NewStatusUpdater returns a new instance of the default StatusUpdater.
func NewStatusUpdater() StatusUpdater {
	return fileUpdater{
		providedFile:  "/conjur/status/CONJUR_SECRETS_PROVIDED",
		updatedFile:   "/conjur/status/CONJUR_SECRETS_UPDATED",
		scripts:       []string{"conjur-secrets-unchanged.sh"},
		scriptSrcDir:  "/usr/local/bin",
		scriptDestDir: "/conjur/status",
		os:            stdOSFuncs,
	}
}

// fileUpdater implements the statusUpdater interface. It records provider
// status by creating empty sentinel files.
type fileUpdater struct {
	providedFile  string
	updatedFile   string
	scripts       []string
	scriptSrcDir  string
	scriptDestDir string
	os            osFuncs
}

func (f fileUpdater) setStatus(path string) error {
	file, err := f.os.create(path)
	if err != nil {
		return err
	}
	defer file.Close()
	return f.os.chmod(file.Name(), statusFileMode)
}

func (f fileUpdater) SetSecretsProvided() error {
	return f.setStatus(f.providedFile)
}

func (f fileUpdater) SetSecretsUpdated() error {
	return f.setStatus(f.updatedFile)
}

func (f fileUpdater) CopyScripts() error {

	// Create the directory
	err := f.os.mkdirAll(f.scriptDestDir, os.ModePerm)
	if err != nil {
		return fmt.Errorf("unable to mkdir at %q: %s", f.scriptDestDir, err)
	}

	for _, script := range f.scripts {
		srcFile := filepath.Join(f.scriptSrcDir, script)
		destFile := filepath.Join(f.scriptDestDir, script)
		if err := f.copyFile(srcFile, destFile); err != nil {
			return err
		}
		if err := f.os.chmod(destFile, scriptFileMode); err != nil {
			return err
		}
	}
	return nil
}

func (f fileUpdater) copyFile(srcPath, destPath string) error {

	src, err := f.os.open(srcPath)
	if err != nil {
		return err
	}
	defer src.Close()
	dest, err := f.os.create(destPath)
	if err != nil {
		return err
	}
	defer dest.Close()
	_, err = io.Copy(dest, src)
	return err
}
