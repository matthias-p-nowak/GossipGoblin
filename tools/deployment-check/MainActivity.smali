# Minimal framework activity; handwritten DEX assembly keeps this probe independent
# of the future product's Gradle/Android SDK setup.
.class public Lorg/gossipgoblin/deploymentcheck/MainActivity;
.super Landroid/app/Activity;

# Construct the platform activity.
.method public constructor <init>()V
    .locals 0
    invoke-direct {p0}, Landroid/app/Activity;-><init>()V
    return-void
.end method

# Show a centered confirmation in the framework TextView held in v0.
.method protected onCreate(Landroid/os/Bundle;)V
    .locals 2
    invoke-super {p0, p1}, Landroid/app/Activity;->onCreate(Landroid/os/Bundle;)V
    new-instance v0, Landroid/widget/TextView;
    invoke-direct {v0, p0}, Landroid/widget/TextView;-><init>(Landroid/content/Context;)V
    const-string v1, "GossipGoblin\n\nDeployment check passed\n\nGreen Monster can install and run apps."
    invoke-virtual {v0, v1}, Landroid/widget/TextView;->setText(Ljava/lang/CharSequence;)V
    const/high16 v1, 0x41c00000    # 24sp text size
    invoke-virtual {v0, v1}, Landroid/widget/TextView;->setTextSize(F)V
    const/16 v1, 0x11            # Center horizontally and vertically
    invoke-virtual {v0, v1}, Landroid/widget/TextView;->setGravity(I)V
    invoke-virtual {p0, v0}, Landroid/app/Activity;->setContentView(Landroid/view/View;)V
    return-void
.end method
