using Dashcam.Api.Models;
using Microsoft.EntityFrameworkCore;

namespace Dashcam.Api.Data;

public sealed class DashcamDbContext(DbContextOptions<DashcamDbContext> options) : DbContext(options)
{
    public DbSet<Video> Videos => Set<Video>();

    protected override void OnModelCreating(ModelBuilder modelBuilder)
    {
        var video = modelBuilder.Entity<Video>();
        video.HasKey(x => x.Id);
        video.Property(x => x.Filename).HasMaxLength(255).IsRequired();
        video.Property(x => x.OriginalFilename).HasMaxLength(255).IsRequired();
        video.Property(x => x.FilePath).HasMaxLength(2048).IsRequired();
        video.HasIndex(x => x.StartTime);
        video.HasIndex(x => x.Locked);
    }
}
