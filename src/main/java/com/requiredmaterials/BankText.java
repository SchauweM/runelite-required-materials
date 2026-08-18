package com.requiredmaterials;

/**
 * A pending "current / goal" quantity label (plus optional tick/cross sprite) queued up
 * while laying out a section, then drawn last so it always overlays the item icons beneath it.
 */
class BankText
{
	final String text;
	final int x;
	final int y;
	final int textColor;
	final int spriteId;
	final int spriteX;
	final int spriteY;

	BankText(String text, int x, int y, int textColor, int spriteId, int spriteX, int spriteY)
	{
		this.text = text;
		this.x = x;
		this.y = y;
		this.textColor = textColor;
		this.spriteId = spriteId;
		this.spriteX = spriteX;
		this.spriteY = spriteY;
	}
}
