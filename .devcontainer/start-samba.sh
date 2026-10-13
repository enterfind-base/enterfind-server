#!/bin/bash
set -e

echo "Initializing Samba..."

# Ensure Samba directories exist and have correct permissions
mkdir -p /run/samba
mkdir -p /var/log/samba
mkdir -p /samba/shares/scratch
# The share root only. Recursing would re-own anything left in the share (a test run's
# enterfind-server-tests folder, say) to nobody with mode 755, after which smbtest is refused
# creating anything inside it with STATUS_ACCESS_DENIED.
chown nobody:nogroup /samba/shares/scratch
chmod 777 /samba/shares/scratch

# Ensure smbtest user exists
if ! id smbtest &>/dev/null; then
    echo "Creating smbtest user..."
    useradd -m -s /usr/sbin/nologin smbtest
fi

# Check if smbtest is already in the Samba password database
if ! pdbedit -L | grep -q "^smbtest:"; then
    echo "Adding smbtest to Samba password database..."
    (echo "smbtest123"; echo "smbtest123") | smbpasswd -a -s smbtest
    echo "Samba user smbtest created successfully"
else
    echo "Samba user smbtest already exists, updating password..."
    (echo "smbtest123"; echo "smbtest123") | smbpasswd -s smbtest
fi

# Start Samba daemons
echo "Starting Samba daemons..."
/usr/sbin/smbd -D
/usr/sbin/nmbd -D

echo "Samba started successfully"
