#!/bin/bash

# Enterfind Server Development Script
# This script manages the devcontainer using the Dev Container CLI from outside the IDE

set -e

# Get script directory in a more portable way
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_NAME="enterfind-server"
DEVCONTAINER_ID="enterfind-server-devcontainer"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Helper functions
log_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

log_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

log_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Check if Docker is running
check_docker() {
    # Check if docker command exists
    if ! command -v docker >/dev/null 2>&1; then
        log_error "Docker command not found. Please install Docker first."
        exit 1
    fi
    
    # Check if Docker daemon is accessible
    if ! docker info >/dev/null 2>&1; then
        log_error "Cannot access Docker daemon. Please check:"
        log_error "  1. Docker daemon is running"
        log_error "  2. Your user has permission to access Docker"
        log_error "  3. Try 'sudo systemctl start docker' or start Docker Desktop"
        log_error "  4. You may need to add your user to the docker group: sudo usermod -aG docker \$USER"
        exit 1
    fi
}

# Check if Dev Container CLI is installed
check_devcontainer_cli() {
    if ! command -v devcontainer &> /dev/null; then
        log_error "Dev Container CLI is not installed."
        log_info "Install it with one of these methods:"
        log_info "  1. npm install -g @devcontainers/cli"
        log_info "  2. Use VS Code to open this folder in a devcontainer"
        log_info "  3. Install using GitHub CLI: gh extension install devcontainers/cli"
        exit 1
    fi
    return 0
}

# Use Docker-based devcontainer CLI (fallback - not typically available)
docker_devcontainer() {
    log_error "Docker-based Dev Container CLI is not available."
    log_info "Please install the Dev Container CLI locally:"
    log_info "  npm install -g @devcontainers/cli"
    exit 1
}

# Build and start the devcontainer
start_devcontainer() {
    log_info "Starting devcontainer..."
    
    check_devcontainer_cli
    
    # Use devcontainer CLI to up the container
    devcontainer up --workspace-folder "$SCRIPT_DIR" --id-label "devcontainer.local_folder=$SCRIPT_DIR"
    
    if [ $? -eq 0 ]; then
        log_success "Devcontainer started successfully"
    else
        log_error "Failed to start devcontainer"
        exit 1
    fi
}

# Stop the devcontainer
stop_devcontainer() {
    log_info "Stopping devcontainer..."
    
    # Find and stop the devcontainer
    CONTAINER_ID=$(docker ps --filter "label=devcontainer.local_folder=$SCRIPT_DIR" --format "{{.ID}}" | head -1)
    
    if [ -n "$CONTAINER_ID" ]; then
        docker stop "$CONTAINER_ID"
        log_success "Devcontainer stopped"
    else
        log_warning "No running devcontainer found"
    fi
}

# Remove the devcontainer
remove_devcontainer() {
    log_info "Removing devcontainer..."
    
    # Find and remove the devcontainer
    CONTAINER_ID=$(docker ps -a --filter "label=devcontainer.local_folder=$SCRIPT_DIR" --format "{{.ID}}" | head -1)
    
    if [ -n "$CONTAINER_ID" ]; then
        docker stop "$CONTAINER_ID" &>/dev/null || true
        docker rm "$CONTAINER_ID"
        log_success "Devcontainer removed"
    else
        log_warning "No devcontainer found to remove"
    fi
}

# Get the running devcontainer ID
get_container_id() {
    docker ps --filter "label=devcontainer.local_folder=$SCRIPT_DIR" --format "{{.ID}}" | head -1
}

# Execute command in devcontainer
devcontainer_exec() {
    local cmd="$*"
    log_info "Executing: $cmd"
    
    CONTAINER_ID=$(get_container_id)
    if [ -z "$CONTAINER_ID" ]; then
        log_warning "Devcontainer is not running. Starting it now..."
        start_devcontainer
        CONTAINER_ID=$(get_container_id)
        
        if [ -z "$CONTAINER_ID" ]; then
            log_error "Failed to start devcontainer"
            exit 1
        fi
    fi
    
    # Test if we can access the container at all
    log_info "Testing container accessibility..."
    if ! docker exec -i "$CONTAINER_ID" echo "test" >/dev/null 2>&1; then
        log_error "Container is not accessible due to mount namespace issues."
        log_info "Container details:"
        docker inspect "$CONTAINER_ID" --format='{{.Config.WorkingDir}}' || true
        
        log_info "Attempting to restart container with proper configuration..."
        docker stop "$CONTAINER_ID" >/dev/null 2>&1 || true
        docker rm "$CONTAINER_ID" >/dev/null 2>&1 || true
        
        start_devcontainer
        CONTAINER_ID=$(get_container_id)
        
        if [ -z "$CONTAINER_ID" ]; then
            log_error "Failed to restart devcontainer"
            exit 1
        fi
        
        # Test again
        if ! docker exec -i "$CONTAINER_ID" echo "test" >/dev/null 2>&1; then
            log_error "Container still not accessible. The devcontainer may not be compatible with this environment."
            exit 1
        fi
    fi
    
    log_info "Container is accessible, executing command..."
    
    # Use -it for interactive terminals, just -i for non-interactive (like CI)
    if [ -t 0 ] && [ -t 1 ]; then
        # Interactive mode
        docker exec -it "$CONTAINER_ID" bash -c "cd /workspaces/enterfind-server && $cmd"
    else
        # Non-interactive mode - ensure we're in the correct working directory
        docker exec -i "$CONTAINER_ID" bash -c "cd /workspaces/enterfind-server && $cmd"
    fi
}

# Execute Maven command in devcontainer
maven_exec() {
    local cmd="$*"
    log_info "Executing: mvn $cmd"
    
    # Check if devcontainer is running, start if not
    CONTAINER_ID=$(get_container_id)
    if [ -z "$CONTAINER_ID" ]; then
        log_warning "Devcontainer is not running. Starting it now..."
        start_devcontainer
    fi
    
    devcontainer_exec "mvn $cmd"
}

# Show container logs
show_logs() {
    CONTAINER_ID=$(get_container_id)
    if [ -n "$CONTAINER_ID" ]; then
        docker logs "$CONTAINER_ID" "$@"
    else
        log_error "No running devcontainer found"
        exit 1
    fi
}

# Enter devcontainer shell
shell() {
    CONTAINER_ID=$(get_container_id)
    if [ -z "$CONTAINER_ID" ]; then
        log_warning "Devcontainer is not running. Starting it now..."
        start_devcontainer
        CONTAINER_ID=$(get_container_id)
    fi
    
    log_info "Opening shell in devcontainer..."
    # Only allow interactive shell if we have a TTY
    if [ -t 0 ] && [ -t 1 ]; then
        docker exec -it "$CONTAINER_ID" bash
    else
        log_error "Cannot open interactive shell in non-TTY environment"
        exit 1
    fi
}

# Show status
status() {
    log_info "Devcontainer status:"
    
    CONTAINER_ID=$(get_container_id)
    if [ -n "$CONTAINER_ID" ]; then
        echo "✅ Devcontainer is running"
        echo "Container ID: $CONTAINER_ID"
        docker ps --format 'table {{.ID}}\t{{.Status}}\t{{.Ports}}' | grep "$CONTAINER_ID"
    else
        STOPPED_CONTAINER=$(docker ps -a --filter "label=devcontainer.local_folder=$SCRIPT_DIR" --format "{{.ID}}" | head -1)
        if [ -n "$STOPPED_CONTAINER" ]; then
            echo "⏸️  Devcontainer exists but is stopped"
            echo "Container ID: $STOPPED_CONTAINER"
            docker ps -a --format 'table {{.ID}}\t{{.Status}}' | grep "$STOPPED_CONTAINER"
        else
            echo "❌ No devcontainer found"
        fi
    fi
    
    log_info "Available devcontainer images:"
    docker images --format 'table {{.Repository}}\t{{.Tag}}\t{{.Size}}' | grep -E "(devcontainer|vsc-enterfind-server)" || echo "No devcontainer images found"
}

# Rebuild devcontainer from scratch
rebuild() {
    log_info "Rebuilding devcontainer from scratch..."
    
    # Remove existing container
    remove_devcontainer
    
    # Remove any cached images
    docker images --format "{{.Repository}}:{{.Tag}}" | grep -E "(devcontainer|vsc-enterfind-server)" | xargs -r docker rmi -f
    
    # Start fresh
    start_devcontainer
}

# Show help
show_help() {
    echo "Enterfind Server Development Script"
    echo ""
    echo "Usage: $0 [COMMAND] [OPTIONS]"
    echo ""
    echo "Prerequisites:"
    echo "  - Docker must be running"
    echo "  - Dev Container CLI must be installed: npm install -g @devcontainers/cli"
    echo ""
    echo "Installation:"
    echo "  npm install -g @devcontainers/cli"
    echo "  # OR using GitHub CLI:"
    echo "  gh extension install devcontainers/cli"
    echo ""
    echo "Devcontainer Management:"
    echo "  stop            Stop the devcontainer"
    echo "  restart         Restart the devcontainer"
    echo "  remove          Remove the devcontainer"
    echo "  rebuild         Remove and start fresh devcontainer"
    echo "  shell           Open a shell in the running devcontainer"
    echo "  logs            Show devcontainer logs"
    echo "  status          Show devcontainer status"
    echo ""
    echo "Maven Commands (auto-start devcontainer if needed):"
    echo "  clean           Run mvn clean"
    echo "  compile         Run mvn compile"
    echo "  test            Run mvn test"
    echo "  package         Run mvn package"
    echo "  install         Run mvn install"
    echo "  run             Run mvn spring-boot:run"
    echo "  maven [cmd]     Run custom maven command"
    echo ""
    echo "Development Shortcuts:"
    echo "  quick-test      Auto-start devcontainer and run tests"
    echo ""
    echo "Examples:"
    echo "  $0 test                   # Auto-start and run tests"
    echo "  $0 run                    # Auto-start and run application"
    echo "  $0 maven clean install    # Auto-start and run custom Maven commands"
    echo "  $0 shell                  # Open shell (auto-starts if needed)"
    echo "  $0 status                 # Check devcontainer status"
    echo ""
    echo "Note: All Maven commands will automatically start the devcontainer if not running."
}

# Main command processing
case "$1" in
    "stop")
        check_docker
        stop_devcontainer
        ;;
    "restart")
        check_docker
        stop_devcontainer
        start_devcontainer
        ;;
    "remove")
        check_docker
        remove_devcontainer
        ;;
    "rebuild")
        check_docker
        rebuild
        ;;
    "shell")
        check_docker
        shell
        ;;
    "logs")
        check_docker
        shift
        show_logs "$@"
        ;;
    "status")
        check_docker
        status
        ;;
    "clean")
        check_docker
        maven_exec "clean"
        ;;
    "compile")
        check_docker
        maven_exec "compile"
        ;;
    "test")
        check_docker
        maven_exec "test"
        ;;
    "package")
        check_docker
        maven_exec "package"
        ;;
    "install")
        check_docker
        maven_exec "install"
        ;;
    "run")
        check_docker
        maven_exec "spring-boot:run"
        ;;
    "maven")
        check_docker
        shift
        maven_exec "$@"
        ;;
    "quick-test")
        check_docker
        
        CONTAINER_ID=$(get_container_id)
        if [ -z "$CONTAINER_ID" ]; then
            start_devcontainer
        fi
        maven_exec "test"
        ;;
    "help"|"--help"|"-h"|"")
        show_help
        ;;
    *)
        log_error "Unknown command: $1"
        echo ""
        show_help
        exit 1
        ;;
esac