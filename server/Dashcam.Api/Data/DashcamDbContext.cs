using Dashcam.Api.Models;
using Microsoft.EntityFrameworkCore;

namespace Dashcam.Api.Data;

public sealed class DashcamDbContext(DbContextOptions<DashcamDbContext> options) : DbContext(options)
{
    public DbSet<Video> Videos => Set<Video>();
    public DbSet<AudioRecording> AudioRecordings => Set<AudioRecording>();

    protected override void OnModelCreating(ModelBuilder modelBuilder)
    {
        var video = modelBuilder.Entity<Video>();
        video.HasKey(x => x.Id);
        video.Property(x => x.Filename).HasMaxLength(255).IsRequired();
        video.Property(x => x.OriginalFilename).HasMaxLength(255).IsRequired();
        video.Property(x => x.FilePath).HasMaxLength(2048).IsRequired();
        video.HasIndex(x => x.StartTime);
        video.HasIndex(x => x.Locked);

        var audio = modelBuilder.Entity<AudioRecording>();
        audio.HasKey(x => x.Id);
        audio.Property(x => x.Filename).HasMaxLength(255).IsRequired();
        audio.Property(x => x.OriginalFilename).HasMaxLength(255).IsRequired();
        audio.Property(x => x.FilePath).HasMaxLength(2048).IsRequired();
        audio.HasIndex(x => x.StartTime);
        audio.HasIndex(x => x.Locked);
    }
}
